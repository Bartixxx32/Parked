// Parked family mode API.
//
// Runs on Cloudflare Workers and stores data in the shared Datalog Supabase
// project, schema `parked`. The Android app only knows about this API; the
// Supabase service role key never leaves the Worker secrets.
//
// Security model:
// - Native app only: no CORS headers and no OPTIONS handler.
// - Every non-health route is rate limited per IP before Supabase is touched;
//   authenticated routes also get a per-device limit.
// - Groups are capped at MAX_GROUP_MEMBERS here and by a database trigger in
//   backend/supabase/003_security_hardening.sql.
// - A scheduled job deletes stale spots, devices and empty groups.

const CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const CODE_LENGTH = 10;
const MAX_BODY_BYTES = 4096;
const MAX_DISPLAY_NAME = 60;
const MAX_ACCURACY_M = 10000;
const MAX_CLOCK_SKEW_MS = 5 * 60 * 1000;
const MAX_SPOT_AGE_MS = 7 * 24 * 60 * 60 * 1000;
const MAX_GROUP_MEMBERS = 20;
const DEFAULT_RETAIN_DAYS = 30;
const RATE_LIMIT_RETRY_SECONDS = 60;

const SECURITY_HEADERS = {
  "cache-control": "no-store",
  "content-security-policy": "default-src 'none'; frame-ancestors 'none'",
  "referrer-policy": "no-referrer",
  "strict-transport-security": "max-age=31536000",
  "x-content-type-options": "nosniff",
  "x-frame-options": "DENY",
};

const encoder = new TextEncoder();

class HttpError extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

export default {
  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (error) {
      if (error instanceof HttpError) return errorResponse(error);
      console.error("unhandled error", error?.stack || error);
      return errorResponse(new HttpError(500, "server_error"));
    }
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(cleanupExpired(env));
  },
};

async function route(request, env) {
  const path = new URL(request.url).pathname.replace(/\/+$/, "") || "/";
  const method = request.method;

  if (method === "GET" && path === "/v1/health") {
    return json({ ok: true, service: "parked" });
  }

  // Pre-auth protection for every API route, including unknown paths. This
  // keeps token guessing and junk traffic away from Supabase.
  await enforceRateLimit(request, env, "GLOBAL_LIMITER", "global");

  if (method === "POST" && path === "/v1/groups") {
    return createGroup(request, env);
  }
  if (method === "POST" && path === "/v1/groups/join") {
    return joinGroup(request, env);
  }
  if (method === "POST" && path === "/v1/groups/rotate") {
    return rotateInviteCode(request, env);
  }
  if (method === "GET" && path === "/v1/spots") {
    return listSpots(request, env);
  }
  if (method === "PUT" && path === "/v1/spot") {
    return upsertSpot(request, env);
  }
  if (method === "PATCH" && path === "/v1/device") {
    return updateDevice(request, env);
  }
  if (method === "DELETE" && path === "/v1/device") {
    return deleteDevice(request, env);
  }
  return errorResponse(new HttpError(404, "not_found"));
}

async function createGroup(request, env) {
  await enforceRateLimit(request, env, "CREATE_LIMITER", "create");

  const body = await readJson(request);
  const displayName = cleanDisplayName(body.displayName);
  if (!displayName) throw new HttpError(400, "invalid_name");

  const inviteCode = generateInviteCode();
  const deviceToken = randomToken();
  const adminToken = randomToken();

  const groups = await supabase(env, "groups", {
    method: "POST",
    body: {
      code_hash: await hashInviteCode(env, inviteCode),
      admin_token_hash: await sha256Hex(adminToken),
    },
    prefer: "return=representation",
  });
  const group = groups?.[0];
  if (!group?.id) throw new HttpError(500, "server_error");

  let devices;
  try {
    devices = await supabase(env, "devices", {
      method: "POST",
      body: {
        group_id: group.id,
        display_name: displayName,
        token_hash: await sha256Hex(deviceToken),
      },
      prefer: "return=representation",
    });
  } catch (error) {
    await supabase(env, `groups?id=eq.${group.id}`, {
      method: "DELETE",
      prefer: "return=minimal",
    }).catch(() => {});
    throw error;
  }
  const device = devices?.[0];
  if (!device?.id) throw new HttpError(500, "server_error");

  return json(
    {
      groupId: group.id,
      deviceId: device.id,
      deviceToken,
      adminToken,
      inviteCode,
    },
    201,
  );
}

async function joinGroup(request, env) {
  await enforceRateLimit(request, env, "JOIN_LIMITER", "join");

  const body = await readJson(request);
  const displayName = cleanDisplayName(body.displayName);
  if (!displayName) throw new HttpError(400, "invalid_name");

  const code = normalizeInviteCode(body.inviteCode);
  if (!code) throw new HttpError(400, "invalid_code");

  const codeHash = await hashInviteCode(env, code);
  const groups = await supabase(
    env,
    `groups?code_hash=eq.${codeHash}&select=id`,
  );
  const group = groups?.[0];
  if (!group?.id) throw new HttpError(404, "invalid_code");

  // The database trigger is the hard cap; this check avoids a pointless insert
  // and returns a friendly error in the normal case.
  const existing = await supabase(
    env,
    `devices?group_id=eq.${group.id}&select=id&limit=${MAX_GROUP_MEMBERS}`,
  );
  if ((existing?.length || 0) >= MAX_GROUP_MEMBERS) {
    throw new HttpError(409, "group_full");
  }

  const deviceToken = randomToken();
  const devices = await supabase(env, "devices", {
    method: "POST",
    body: {
      group_id: group.id,
      display_name: displayName,
      token_hash: await sha256Hex(deviceToken),
    },
    prefer: "return=representation",
  });
  const device = devices?.[0];
  if (!device?.id) throw new HttpError(500, "server_error");

  return json({
    groupId: group.id,
    deviceId: device.id,
    deviceToken,
    inviteCode: code,
  });
}

async function listSpots(request, env) {
  const device = await requireDevice(request, env);
  await enforceRateLimit(request, env, "READ_LIMITER", "read", device.id);

  const now = new Date().toISOString();

  await supabase(env, `devices?id=eq.${device.id}`, {
    method: "PATCH",
    body: { last_seen_at: now },
    prefer: "return=minimal",
  });

  const devices = await supabase(
    env,
    `devices?group_id=eq.${device.group_id}&select=id,display_name,last_seen_at`,
  );
  const spots = await supabase(
    env,
    `spots?group_id=eq.${device.group_id}&select=device_id,latitude,longitude,accuracy_m,saved_at,updated_at`,
  );
  const spotByDevice = new Map((spots || []).map((spot) => [spot.device_id, spot]));

  const members = (devices || []).map((member) => {
    const spot = spotByDevice.get(member.id);
    return {
      deviceId: member.id,
      displayName: member.display_name,
      lastSeenAt: member.last_seen_at,
      spot: spot
        ? {
            latitude: spot.latitude,
            longitude: spot.longitude,
            accuracyM: spot.accuracy_m,
            savedAt: spot.saved_at,
          }
        : null,
    };
  });

  return json({ groupId: device.group_id, members });
}

async function upsertSpot(request, env) {
  const device = await requireDevice(request, env);
  await enforceRateLimit(request, env, "WRITE_LIMITER", "write", device.id);
  const body = await readJson(request);

  if (!inRange(body.latitude, -90, 90) || !inRange(body.longitude, -180, 180)) {
    throw new HttpError(400, "invalid_coordinates");
  }
  const accuracy = body.accuracyM;
  if (
    accuracy !== null &&
    accuracy !== undefined &&
    !inRange(accuracy, 0, MAX_ACCURACY_M)
  ) {
    throw new HttpError(400, "invalid_accuracy");
  }

  const now = new Date().toISOString();
  await supabase(env, "spots?on_conflict=device_id", {
    method: "POST",
    body: {
      group_id: device.group_id,
      device_id: device.id,
      latitude: body.latitude,
      longitude: body.longitude,
      accuracy_m: accuracy ?? null,
      saved_at: clientTimeOrNow(body.savedAt),
      updated_at: now,
    },
    prefer: "resolution=merge-duplicates,return=minimal",
  });
  await supabase(env, `devices?id=eq.${device.id}`, {
    method: "PATCH",
    body: { last_seen_at: now },
    prefer: "return=minimal",
  });

  return empty(204);
}

async function updateDevice(request, env) {
  const device = await requireDevice(request, env);
  await enforceRateLimit(request, env, "WRITE_LIMITER", "write", device.id);
  const body = await readJson(request);
  const displayName = cleanDisplayName(body.displayName);
  if (!displayName) throw new HttpError(400, "invalid_name");

  await supabase(env, `devices?id=eq.${device.id}`, {
    method: "PATCH",
    body: { display_name: displayName },
    prefer: "return=minimal",
  });
  return empty(204);
}

async function deleteDevice(request, env) {
  const device = await requireDevice(request, env);
  await enforceRateLimit(request, env, "WRITE_LIMITER", "write", device.id);

  await supabase(env, `devices?id=eq.${device.id}`, {
    method: "DELETE",
    prefer: "return=minimal",
  });

  const remaining = await supabase(
    env,
    `devices?group_id=eq.${device.group_id}&select=id&limit=1`,
  );
  if (!remaining || remaining.length === 0) {
    await supabase(env, `groups?id=eq.${device.group_id}`, {
      method: "DELETE",
      prefer: "return=minimal",
    });
  }

  return empty(204);
}

async function rotateInviteCode(request, env) {
  const device = await requireDevice(request, env);
  await enforceRateLimit(request, env, "ROTATE_LIMITER", "rotate", device.id);
  await requireAdmin(request, env, device);

  const inviteCode = generateInviteCode();
  await supabase(env, `groups?id=eq.${device.group_id}`, {
    method: "PATCH",
    body: {
      code_hash: await hashInviteCode(env, inviteCode),
      rotated_at: new Date().toISOString(),
    },
    prefer: "return=minimal",
  });

  return json({ inviteCode });
}

async function requireDevice(request, env) {
  const header = request.headers.get("authorization") || "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  if (!match) throw new HttpError(401, "unauthorized");

  const token = match[1].trim();
  if (token.length < 20 || token.length > 200) {
    throw new HttpError(401, "unauthorized");
  }

  const tokenHash = await sha256Hex(token);
  const devices = await supabase(
    env,
    `devices?token_hash=eq.${tokenHash}&select=id,group_id`,
  );
  const device = devices?.[0];
  if (!device) throw new HttpError(401, "unauthorized");
  return device;
}

async function requireAdmin(request, env, device) {
  const adminToken = (request.headers.get("x-admin-token") || "").trim();
  if (!adminToken) throw new HttpError(403, "forbidden");

  const groups = await supabase(
    env,
    `groups?id=eq.${device.group_id}&select=admin_token_hash`,
  );
  const group = groups?.[0];
  if (!group?.admin_token_hash) throw new HttpError(403, "forbidden");

  const candidate = await sha256Hex(adminToken);
  if (!timingSafeEqual(candidate, group.admin_token_hash)) {
    throw new HttpError(403, "forbidden");
  }
}

async function enforceRateLimit(request, env, bindingName, scope, actor) {
  const limiter = env?.[bindingName];
  if (!limiter || typeof limiter.limit !== "function") return;

  const key = (actor || request.headers.get("cf-connecting-ip") || "unknown")
    .toString()
    .slice(0, 180);
  let result;
  try {
    result = await limiter.limit({ key: `${scope}:${key}` });
  } catch (error) {
    // Fail open: a broken limiter must not take the family server down.
    console.error("rate limiter failed", bindingName, error);
    return;
  }
  if (!result?.success) throw new HttpError(429, "rate_limited");
}

async function cleanupExpired(env) {
  const retainDays = clampInt(env.RETAIN_DAYS, 1, 365, DEFAULT_RETAIN_DAYS);
  const cutoff = new Date(
    Date.now() - retainDays * 24 * 60 * 60 * 1000,
  ).toISOString();
  const encoded = encodeURIComponent(cutoff);

  // Spot rows are the actual location data; remove them first. Deleting stale
  // devices also cascades to their spots.
  await supabase(env, `spots?updated_at=lt.${encoded}`, {
    method: "DELETE",
    prefer: "return=minimal",
  });
  await supabase(env, `devices?last_seen_at=lt.${encoded}`, {
    method: "DELETE",
    prefer: "return=minimal",
  });
  await supabase(
    env,
    `devices?last_seen_at=is.null&created_at=lt.${encoded}`,
    {
      method: "DELETE",
      prefer: "return=minimal",
    },
  );

  // Remove old groups that have no members left. Only groups older than the
  // cutoff are considered, so a group created moments ago cannot be deleted
  // between the group and device inserts.
  const groups = await supabase(
    env,
    `groups?created_at=lt.${encoded}&select=id`,
  );
  if (!groups?.length) return;

  const devices = await supabase(env, "devices?select=group_id");
  const usedGroupIds = new Set((devices || []).map((item) => item.group_id));
  for (const group of groups) {
    if (usedGroupIds.has(group.id)) continue;
    await supabase(env, `groups?id=eq.${group.id}`, {
      method: "DELETE",
      prefer: "return=minimal",
    });
  }
}

async function readJson(request) {
  const declaredLength = Number(request.headers.get("content-length") || "0");
  if (!Number.isFinite(declaredLength) || declaredLength > MAX_BODY_BYTES) {
    throw new HttpError(413, "body_too_large");
  }
  const text = await request.text();
  if (text.length > MAX_BODY_BYTES) {
    throw new HttpError(413, "body_too_large");
  }
  let value;
  try {
    value = JSON.parse(text);
  } catch {
    throw new HttpError(400, "invalid_json");
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new HttpError(400, "invalid_json");
  }
  return value;
}

async function supabase(env, path, { method = "GET", body, prefer } = {}) {
  if (!env.SUPABASE_URL || !env.SUPABASE_SERVICE_ROLE_KEY) {
    throw new HttpError(500, "server_error");
  }
  const headers = {
    apikey: env.SUPABASE_SERVICE_ROLE_KEY,
    authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
    accept: "application/json",
    "accept-profile": "parked",
    "content-profile": "parked",
  };
  if (body !== undefined) headers["content-type"] = "application/json";
  if (prefer) headers.prefer = prefer;

  const response = await fetch(
    `${env.SUPABASE_URL.replace(/\/$/, "")}/rest/v1/${path}`,
    {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    },
  );
  if (!response.ok) {
    const detail = await response.text();
    console.error(
      "supabase request failed",
      method,
      path.split("?")[0],
      response.status,
      detail.slice(0, 300),
    );
    if (detail.includes("group_full")) {
      throw new HttpError(409, "group_full");
    }
    throw new HttpError(500, "server_error");
  }
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

function cleanDisplayName(raw) {
  if (typeof raw !== "string") return null;
  const name = raw.replace(/[\u0000-\u001F\u007F]/g, "").trim();
  if (name.length < 1 || name.length > MAX_DISPLAY_NAME) return null;
  return name;
}

function normalizeInviteCode(raw) {
  if (typeof raw !== "string") return null;
  const code = raw
    .toUpperCase()
    .replace(/[^0-9A-Z]/g, "")
    .replace(/O/g, "0")
    .replace(/[IL]/g, "1");
  return new RegExp(`^[0-9A-HJKMNP-TV-Z]{${CODE_LENGTH}}$`).test(code)
    ? code
    : null;
}

function generateInviteCode() {
  const bytes = new Uint8Array(CODE_LENGTH);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => CODE_ALPHABET[byte & 31]).join("");
}

function randomToken() {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

async function sha256Hex(value) {
  return toHex(await crypto.subtle.digest("SHA-256", encoder.encode(value)));
}

async function hashInviteCode(env, code) {
  if (!env.CODE_PEPPER) throw new HttpError(500, "server_error");
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(env.CODE_PEPPER),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "HMAC",
    key,
    encoder.encode(`invite:${code}`),
  );
  return toHex(signature);
}

function toHex(buffer) {
  return Array.from(new Uint8Array(buffer), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
}

function timingSafeEqual(left, right) {
  if (typeof left !== "string" || typeof right !== "string") return false;
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let index = 0; index < left.length; index += 1) {
    difference |= left.charCodeAt(index) ^ right.charCodeAt(index);
  }
  return difference === 0;
}

function inRange(value, min, max) {
  return (
    typeof value === "number" &&
    Number.isFinite(value) &&
    value >= min &&
    value <= max
  );
}

function clientTimeOrNow(raw) {
  if (typeof raw !== "string") return new Date().toISOString();
  const parsed = Date.parse(raw);
  const now = Date.now();
  if (
    !Number.isFinite(parsed) ||
    parsed > now + MAX_CLOCK_SKEW_MS ||
    parsed < now - MAX_SPOT_AGE_MS
  ) {
    return new Date(now).toISOString();
  }
  return new Date(parsed).toISOString();
}

function clampInt(value, min, max, fallback) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

function json(body, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      ...SECURITY_HEADERS,
      "content-type": "application/json; charset=utf-8",
      ...extraHeaders,
    },
  });
}

function empty(status = 204) {
  return new Response(null, { status, headers: SECURITY_HEADERS });
}

function errorResponse(error) {
  const headers =
    error.status === 429
      ? { "retry-after": String(RATE_LIMIT_RETRY_SECONDS) }
      : {};
  return json({ error: error.code }, error.status, headers);
}

// Exported for the Node test suite. Cloudflare Workers ignores named exports.
export {
  MAX_GROUP_MEMBERS,
  cleanDisplayName,
  clientTimeOrNow,
  generateInviteCode,
  hashInviteCode,
  inRange,
  normalizeInviteCode,
  randomToken,
  sha256Hex,
  timingSafeEqual,
  toHex,
};
