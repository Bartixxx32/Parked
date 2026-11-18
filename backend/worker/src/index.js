// Parked family mode API.
//
// Runs on Cloudflare Workers and stores data in the shared Datalog Supabase
// project, schema `parked`. The Android app only knows about this API; the
// Supabase service role key never leaves the Worker secrets.

const CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const CODE_LENGTH = 10;
const MAX_BODY_BYTES = 4096;
const MAX_DISPLAY_NAME = 60;
const MAX_ACCURACY_M = 10000;
const MAX_CLOCK_SKEW_MS = 5 * 60 * 1000;
const MAX_SPOT_AGE_MS = 7 * 24 * 60 * 60 * 1000;

const CORS_HEADERS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, POST, PUT, PATCH, DELETE, OPTIONS",
  "access-control-allow-headers": "authorization, content-type, x-admin-token",
  "access-control-max-age": "86400",
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
};

async function route(request, env) {
  const path = new URL(request.url).pathname.replace(/\/+$/, "") || "/";
  const method = request.method;

  if (method === "OPTIONS") return empty(204);
  if (method === "GET" && path === "/v1/health") {
    return json({ ok: true, service: "parked" });
  }
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
  if (candidate !== group.admin_token_hash) {
    throw new HttpError(403, "forbidden");
  }
}

async function readJson(request) {
  const declaredLength = Number(request.headers.get("content-length") || "0");
  if (declaredLength > MAX_BODY_BYTES) {
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

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      ...CORS_HEADERS,
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
    },
  });
}

function empty(status = 204) {
  return new Response(null, { status, headers: CORS_HEADERS });
}

function errorResponse(error) {
  return json({ error: error.code }, error.status);
}
