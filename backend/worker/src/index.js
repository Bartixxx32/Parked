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
const MAX_ENCRYPTED_PAYLOAD = 2048;
const MAX_KEY_VERSION = 1_000_000;
const ENCRYPTED_DISPLAY_NAME = "Encrypted";
const CRYPTO_KEY_SALT_LENGTH = 22;
const CRYPTO_WRAPPED_KEY_LENGTH = 64;
const CRYPTO_KEY_IV_LENGTH = 16;
const CRYPTO_JOIN_AUTH_KEY_LENGTH = 43;
const MAX_PHOTO_BYTES = 2_000_000;
const MAX_PHOTO_PENDING_PER_GROUP = 5;
const MAX_PHOTO_PENDING_PER_DEVICE = 3;
const MAX_PHOTOS_PER_CLEANUP = 100;
// A small ownership record outlives the photo object so a sender can still
// retract a wrong photo after every family phone has downloaded it. Unrevoked
// records are pruned after this many days; revoked records are kept this long
// after the retraction so phones that were offline can still learn about it.
const PHOTO_RECORD_RETENTION_DAYS = 30;
const MAX_REVOCATIONS_PER_LIST = 100;
const DEFAULT_PHOTO_RETENTION_HOURS = 24;
const MAX_PHOTO_RETENTION_HOURS = 168;
const DEFAULT_PHOTO_BUCKET = "parked-photos";
const PHOTO_ID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const PHOTO_IV_RE = /^[A-Za-z0-9_-]{16}$/;

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
    // The hourly run only enforces the photo TTL. The daily run also removes
    // stale spots, devices and empty groups.
    ctx.waitUntil(
      event?.cron === "17 * * * *"
        ? cleanupExpiredPhotos(env)
        : cleanupExpired(env),
    );
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
  if (method === "DELETE" && path === "/v1/spot") {
    return deleteSpot(request, env);
  }
  if (method === "POST" && path === "/v1/photos") {
    return uploadPhoto(request, env);
  }
  if (method === "GET" && path === "/v1/photos") {
    return listPhotos(request, env);
  }
  const photoMatch = path.match(/^\/v1\/photos\/([^/]+)$/);
  if (method === "GET" && photoMatch) {
    return downloadPhoto(request, env, photoMatch[1]);
  }
  if (method === "DELETE" && photoMatch) {
    return deletePhoto(request, env, photoMatch[1]);
  }
  const photoAckMatch = path.match(/^\/v1\/photos\/([^/]+)\/ack$/);
  if (method === "POST" && photoAckMatch) {
    return ackPhoto(request, env, photoAckMatch[1]);
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
  const cryptoPayload = parseCryptoPayload(body.crypto);

  const inviteCode = generateInviteCode();
  const deviceToken = randomToken();
  const adminToken = randomToken();

  const groupBody = {
    code_hash: await hashInviteCode(env, inviteCode),
    admin_token_hash: await sha256Hex(adminToken),
  };
  if (cryptoPayload) {
    groupBody.crypto_mode = "e2ee";
    groupBody.key_salt = cryptoPayload.keySalt;
    groupBody.wrapped_key = cryptoPayload.wrappedKey;
    groupBody.key_iv = cryptoPayload.keyIv;
    groupBody.join_auth_hash = await hashJoinAuthKey(env, cryptoPayload.joinAuthKey);
    groupBody.key_version = 1;
  }

  const groups = await supabase(env, "groups", {
    method: "POST",
    body: groupBody,
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
        display_name: cryptoPayload ? ENCRYPTED_DISPLAY_NAME : displayName,
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

  const response = {
    groupId: group.id,
    deviceId: device.id,
    deviceToken,
    adminToken,
    inviteCode,
  };
  if (cryptoPayload) {
    response.crypto = cryptoResponse({
      key_salt: cryptoPayload.keySalt,
      wrapped_key: cryptoPayload.wrappedKey,
      key_iv: cryptoPayload.keyIv,
      key_version: 1,
    });
  }
  return json(response, 201);
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
    `groups?code_hash=eq.${codeHash}&select=id,crypto_mode,key_salt,wrapped_key,key_iv,key_version,join_auth_hash`,
  );
  const group = groups?.[0];
  if (!group?.id) throw new HttpError(404, "invalid_code");

  const encrypted = group.crypto_mode === "e2ee";
  if (encrypted) {
    const authKey = normalizeJoinAuthKey(body.joinAuthKey);
    if (!authKey) throw new HttpError(403, "invite_link_required");
    const candidate = await hashJoinAuthKey(env, authKey);
    if (!timingSafeEqual(candidate, group.join_auth_hash || "")) {
      throw new HttpError(403, "invalid_invite");
    }
  }

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
      display_name: encrypted ? ENCRYPTED_DISPLAY_NAME : displayName,
      token_hash: await sha256Hex(deviceToken),
    },
    prefer: "return=representation",
  });
  const device = devices?.[0];
  if (!device?.id) throw new HttpError(500, "server_error");

  const response = {
    groupId: group.id,
    deviceId: device.id,
    deviceToken,
    inviteCode: code,
  };
  if (encrypted) response.crypto = cryptoResponse(group);
  return json(response);
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

  const group = await getGroup(env, device.group_id);
  const encrypted = group?.crypto_mode === "e2ee";

  const devices = await supabase(
    env,
    `devices?group_id=eq.${device.group_id}&select=id,display_name,last_seen_at`,
  );
  const spots = await supabase(
    env,
    `spots?group_id=eq.${device.group_id}&select=device_id,latitude,longitude,accuracy_m,payload,key_version,saved_at,updated_at`,
  );
  const spotByDevice = new Map((spots || []).map((spot) => [spot.device_id, spot]));

  const members = (devices || []).map((member) => {
    const spot = spotByDevice.get(member.id);
    if (encrypted) {
      return {
        deviceId: member.id,
        lastSeenAt: member.last_seen_at,
        spot: spot
          ? {
              payload: spot.payload,
              keyVersion: spot.key_version,
              savedAt: spot.saved_at,
            }
          : null,
      };
    }
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

  return json({
    groupId: device.group_id,
    cryptoMode: encrypted ? "e2ee" : "plaintext",
    members,
  });
}

async function upsertSpot(request, env) {
  const device = await requireDevice(request, env);
  await enforceRateLimit(request, env, "WRITE_LIMITER", "write", device.id);
  const body = await readJson(request);
  const group = await getGroup(env, device.group_id);
  const now = new Date().toISOString();

  if (group?.crypto_mode === "e2ee") {
    const payload = cleanEncryptedPayload(body.payload);
    const keyVersion = cleanKeyVersion(body.keyVersion);
    if (!payload || keyVersion === null) {
      throw new HttpError(400, "invalid_crypto");
    }
    if (keyVersion !== group.key_version) {
      throw new HttpError(409, "key_version_mismatch");
    }
    await supabase(env, "spots?on_conflict=device_id", {
      method: "POST",
      body: {
        group_id: device.group_id,
        device_id: device.id,
        latitude: null,
        longitude: null,
        accuracy_m: null,
        payload,
        key_version: keyVersion,
        saved_at: clientTimeOrNow(body.savedAt),
        updated_at: now,
      },
      prefer: "resolution=merge-duplicates,return=minimal",
    });
  } else {
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
  }
  await supabase(env, `devices?id=eq.${device.id}`, {
    method: "PATCH",
    body: { last_seen_at: now },
    prefer: "return=minimal",
  });

  return empty(204);
}

async function deleteSpot(request, env) {
  const device = await requireDevice(request, env);
  await enforceRateLimit(request, env, "WRITE_LIMITER", "write", device.id);

  // The spot is already ciphertext for E2EE groups, so removing it needs no
  // key material and works exactly the same for legacy plaintext groups.
  await supabase(env, `spots?device_id=eq.${device.id}`, {
    method: "DELETE",
    prefer: "return=minimal",
  });
  await supabase(env, `devices?id=eq.${device.id}`, {
    method: "PATCH",
    body: { last_seen_at: new Date().toISOString() },
    prefer: "return=minimal",
  });

  return empty(204);
}

async function uploadPhoto(request, env) {
  const device = await requireDevice(request, env);
  if (!photoUploadEnabled(env)) {
    // The operator can switch off new uploads when the free Storage tier is
    // under pressure. Spot sharing and the rest of the API keep working.
    throw new HttpError(503, "photos_disabled");
  }
  await enforceRateLimit(request, env, "WRITE_LIMITER", "photo-upload", device.id);

  const group = await getGroup(env, device.group_id);
  if (group?.crypto_mode !== "e2ee") {
    // A plaintext group has no key the Worker does not know, so accepting the
    // upload would mean storing a photo the server can read. Refuse instead.
    throw new HttpError(400, "crypto_required");
  }

  const photoId = cleanPhotoId(request.headers.get("x-photo-id"));
  const iv = cleanPhotoIv(request.headers.get("x-photo-iv"));
  const keyVersion = cleanKeyVersion(
    Number(request.headers.get("x-key-version") || ""),
  );
  if (!photoId || !iv || keyVersion === null) {
    throw new HttpError(400, "invalid_photo");
  }
  if (keyVersion !== group.key_version) {
    throw new HttpError(409, "key_version_mismatch");
  }

  const bytes = await readLimitedBytes(request, MAX_PHOTO_BYTES);
  if (bytes.byteLength < 16) throw new HttpError(400, "invalid_photo");

  const now = new Date();
  const encodedNow = encodeURIComponent(now.toISOString());
  const [groupPending, devicePending] = await Promise.all([
    supabase(
      env,
      `photos?group_id=eq.${device.group_id}&expires_at=gt.${encodedNow}&select=id&limit=${MAX_PHOTO_PENDING_PER_GROUP}`,
    ),
    supabase(
      env,
      `photos?sender_device_id=eq.${device.id}&expires_at=gt.${encodedNow}&select=id&limit=${MAX_PHOTO_PENDING_PER_DEVICE}`,
    ),
  ]);
  if (
    (groupPending?.length || 0) >= MAX_PHOTO_PENDING_PER_GROUP ||
    (devicePending?.length || 0) >= MAX_PHOTO_PENDING_PER_DEVICE
  ) {
    // Photos are deleted as soon as every phone confirms, so this is a
    // temporary cap that keeps the free Storage bucket bounded.
    throw new HttpError(409, "photo_limit");
  }

  const devices = await supabase(
    env,
    `devices?group_id=eq.${device.group_id}&select=id`,
  );
  const recipients = (devices || [])
    .map((member) => member.id)
    .filter((id) => id !== device.id);
  if (recipients.length === 0) {
    throw new HttpError(409, "no_recipients");
  }

  const objectPath = `${device.group_id}/${photoId}`;
  await storageUpload(env, objectPath, bytes);

  const expiresAt = new Date(
    now.getTime() + photoRetentionHours(env) * 60 * 60 * 1000,
  ).toISOString();
  try {
    await supabase(env, "photos", {
      method: "POST",
      body: {
        id: photoId,
        group_id: device.group_id,
        sender_device_id: device.id,
        object_path: objectPath,
        iv,
        key_version: keyVersion,
        size_bytes: bytes.byteLength,
        created_at: now.toISOString(),
        expires_at: expiresAt,
      },
      prefer: "return=minimal",
    });
    await supabase(env, "photo_records", {
      method: "POST",
      body: {
        photo_id: photoId,
        group_id: device.group_id,
        sender_device_id: device.id,
        created_at: now.toISOString(),
      },
      prefer: "return=minimal",
    });
    await supabase(env, "photo_deliveries", {
      method: "POST",
      body: recipients.map((deviceId) => ({
        photo_id: photoId,
        device_id: deviceId,
      })),
      prefer: "return=minimal",
    });
  } catch (error) {
    await storageDelete(env, objectPath).catch(() => {});
    await supabase(env, `photos?id=eq.${photoId}`, {
      method: "DELETE",
      prefer: "return=minimal",
    }).catch(() => {});
    await supabase(env, `photo_records?photo_id=eq.${photoId}`, {
      method: "DELETE",
      prefer: "return=minimal",
    }).catch(() => {});
    throw error;
  }

  return json(
    {
      id: photoId,
      expiresAt,
      recipients: recipients.length,
    },
    201,
  );
}

async function listPhotos(request, env) {
  const device = await requireDevice(request, env);
  await enforceRateLimit(request, env, "READ_LIMITER", "photo-list", device.id);

  // Clients use this capability flag to hide the "Send to family" action
  // instead of attempting an upload that the server would reject.
  const uploadEnabled = photoUploadEnabled(env);
  const revocationCutoff = new Date(
    Date.now() - PHOTO_RECORD_RETENTION_DAYS * 24 * 60 * 60 * 1000,
  ).toISOString();
  const [deliveries, revocations] = await Promise.all([
    supabase(
      env,
      `photo_deliveries?device_id=eq.${device.id}&acked_at=is.null&select=photo_id&limit=20`,
    ),
    supabase(
      env,
      `photo_records?group_id=eq.${device.group_id}&revoked_at=gt.${encodeURIComponent(revocationCutoff)}&select=photo_id&order=revoked_at.desc&limit=${MAX_REVOCATIONS_PER_LIST}`,
    ),
  ]);
  const ids = [
    ...new Set(
      (deliveries || [])
        .map((delivery) => delivery.photo_id)
        .filter(Boolean),
    ),
  ];
  const revokedPhotoIds = (revocations || [])
    .map((record) => record.photo_id)
    .filter(Boolean);
  if (ids.length === 0) {
    return json({ photos: [], uploadEnabled, revokedPhotoIds });
  }

  const photos = await supabase(
    env,
    `photos?id=in.(${ids.join(",")})&expires_at=gt.${encodeURIComponent(new Date().toISOString())}&select=id,sender_device_id,iv,key_version,size_bytes,created_at,expires_at&order=created_at.asc`,
  );
  return json({
    photos: (photos || []).map((photo) => ({
      id: photo.id,
      senderDeviceId: photo.sender_device_id,
      iv: photo.iv,
      keyVersion: photo.key_version,
      sizeBytes: photo.size_bytes,
      createdAt: photo.created_at,
      expiresAt: photo.expires_at,
    })),
    uploadEnabled,
    revokedPhotoIds,
  });
}

async function downloadPhoto(request, env, rawPhotoId) {
  const photoId = cleanPhotoId(rawPhotoId);
  if (!photoId) throw new HttpError(404, "not_found");

  const device = await requireDevice(request, env);
  await enforceRateLimit(request, env, "READ_LIMITER", "photo-download", device.id);

  const deliveries = await supabase(
    env,
    `photo_deliveries?photo_id=eq.${photoId}&device_id=eq.${device.id}&select=photo_id`,
  );
  if (!deliveries?.[0]) throw new HttpError(404, "not_found");

  const photos = await supabase(
    env,
    `photos?id=eq.${photoId}&select=id,group_id,object_path,iv,key_version,expires_at`,
  );
  const photo = photos?.[0];
  if (!photo || photo.group_id !== device.group_id) {
    throw new HttpError(404, "not_found");
  }
  if (Date.parse(photo.expires_at) <= Date.now()) {
    throw new HttpError(404, "not_found");
  }

  let object;
  try {
    object = await storageDownload(env, photo.object_path);
  } catch (error) {
    if (error instanceof HttpError && error.status === 404) {
      // Storage lost the object (for example a failed cleanup). Drop the
      // metadata so recipients stop seeing a photo that can never download.
      await supabase(env, `photos?id=eq.${photoId}`, {
        method: "DELETE",
        prefer: "return=minimal",
      }).catch(() => {});
    }
    throw error;
  }
  const headers = {
    ...SECURITY_HEADERS,
    "content-type": "application/octet-stream",
    "cache-control": "no-store",
    "x-photo-iv": photo.iv,
    "x-key-version": String(photo.key_version),
  };
  const length = object.headers.get("content-length");
  if (length) headers["content-length"] = length;
  return new Response(object.body, { status: 200, headers });
}

async function ackPhoto(request, env, rawPhotoId) {
  const photoId = cleanPhotoId(rawPhotoId);
  if (!photoId) throw new HttpError(404, "not_found");

  const device = await requireDevice(request, env);
  await enforceRateLimit(request, env, "WRITE_LIMITER", "photo-ack", device.id);

  const deliveries = await supabase(
    env,
    `photo_deliveries?photo_id=eq.${photoId}&device_id=eq.${device.id}&select=photo_id`,
  );
  if (!deliveries?.[0]) throw new HttpError(404, "not_found");

  await supabase(
    env,
    `photo_deliveries?photo_id=eq.${photoId}&device_id=eq.${device.id}`,
    {
      method: "PATCH",
      body: { acked_at: new Date().toISOString() },
      prefer: "return=minimal",
    },
  );

  await deletePhotoIfDelivered(env, photoId);
  return empty(204);
}

async function deletePhoto(request, env, rawPhotoId) {
  const photoId = cleanPhotoId(rawPhotoId);
  if (!photoId) throw new HttpError(404, "not_found");

  const device = await requireDevice(request, env);
  await enforceRateLimit(request, env, "WRITE_LIMITER", "photo-delete", device.id);

  const [photos, records] = await Promise.all([
    supabase(
      env,
      `photos?id=eq.${photoId}&group_id=eq.${device.group_id}&select=id,sender_device_id,object_path,created_at`,
    ),
    supabase(
      env,
      `photo_records?photo_id=eq.${photoId}&group_id=eq.${device.group_id}&select=photo_id,sender_device_id`,
    ),
  ]);
  const photo = photos?.[0];
  const record = records?.[0];
  if (!photo && !record) throw new HttpError(404, "not_found");

  const senderId = record?.sender_device_id || photo?.sender_device_id;
  if (senderId !== device.id) {
    throw new HttpError(403, "forbidden");
  }

  if (photo) {
    await storageDelete(env, photo.object_path);
    await supabase(env, `photos?id=eq.${photoId}`, {
      method: "DELETE",
      prefer: "return=minimal",
    });
  }

  const now = new Date().toISOString();
  if (record) {
    // Keep the timestamp when the sender repeats the same request.
    await supabase(
      env,
      `photo_records?photo_id=eq.${photoId}&revoked_at=is.null`,
      {
        method: "PATCH",
        body: { revoked_at: now },
        prefer: "return=minimal",
      },
    );
  } else {
    // Photos uploaded before the ownership table existed still become
    // revocable once their sender deletes them.
    await supabase(env, "photo_records", {
      method: "POST",
      body: {
        photo_id: photoId,
        group_id: device.group_id,
        sender_device_id: device.id,
        created_at: photo?.created_at || now,
        revoked_at: now,
      },
      prefer: "resolution=merge-duplicates,return=minimal",
    });
  }
  return empty(204);
}

/**
 * Removes the Storage object once every recipient has confirmed the download.
 * Keeping the row until the object is gone lets the hourly cleanup retry a
 * failed Storage delete instead of orphaning the ciphertext.
 */
async function deletePhotoIfDelivered(env, photoId) {
  const remaining = await supabase(
    env,
    `photo_deliveries?photo_id=eq.${photoId}&acked_at=is.null&select=device_id&limit=1`,
  );
  if ((remaining?.length || 0) > 0) return false;

  const photos = await supabase(
    env,
    `photos?id=eq.${photoId}&select=object_path`,
  );
  const photo = photos?.[0];
  if (!photo) return true;

  await storageDelete(env, photo.object_path);
  await supabase(env, `photos?id=eq.${photoId}`, {
    method: "DELETE",
    prefer: "return=minimal",
  });
  return true;
}

/**
 * A device leaving can remove the last unacked recipient. Re-check the group's
 * pending photos so a photo does not wait for the TTL after everyone who still
 * belongs to the group has already downloaded it.
 */
async function reconcileGroupPhotos(env, groupId) {
  const photos = await supabase(
    env,
    `photos?group_id=eq.${groupId}&select=id&limit=${MAX_PHOTOS_PER_CLEANUP}`,
  );
  for (const photo of photos || []) {
    await deletePhotoIfDelivered(env, photo.id).catch((error) => {
      console.error("photo reconcile failed", photo.id, error?.message || error);
    });
  }
}

async function updateDevice(request, env) {
  const device = await requireDevice(request, env);
  await enforceRateLimit(request, env, "WRITE_LIMITER", "write", device.id);
  const body = await readJson(request);
  const displayName = cleanDisplayName(body.displayName);
  if (!displayName) throw new HttpError(400, "invalid_name");

  const group = await getGroup(env, device.group_id);
  if (group?.crypto_mode === "e2ee") {
    // The label is encrypted inside the spot payload; the server must not
    // learn it. The phone re-uploads the latest spot with the new label.
    return empty(204);
  }

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

  const remaining = await supabase(
    env,
    `devices?group_id=eq.${device.group_id}&id=neq.${device.id}&select=id&limit=1`,
  );
  const lastDevice = !remaining || remaining.length === 0;

  // Deleting the device cascades its photo rows. Remove the matching Storage
  // objects first so the ciphertext does not outlive the database record.
  const photos = await supabase(
    env,
    lastDevice
      ? `photos?group_id=eq.${device.group_id}&select=object_path&limit=${MAX_PHOTOS_PER_CLEANUP}`
      : `photos?sender_device_id=eq.${device.id}&select=object_path&limit=${MAX_PHOTOS_PER_CLEANUP}`,
  );
  for (const photo of photos || []) {
    await storageDelete(env, photo.object_path).catch((error) => {
      console.error("photo object delete failed", error?.message || error);
    });
  }

  if (!lastDevice) {
    // Ownership records outlive the device. Mark them revoked first, so
    // family phones remove photos they already downloaded even if the app did
    // not get a chance to retract them before leaving.
    await supabase(
      env,
      `photo_records?sender_device_id=eq.${device.id}&revoked_at=is.null`,
      {
        method: "PATCH",
        body: { revoked_at: new Date().toISOString() },
        prefer: "return=minimal",
      },
    );
  }

  await supabase(env, `devices?id=eq.${device.id}`, {
    method: "DELETE",
    prefer: "return=minimal",
  });

  if (lastDevice) {
    await supabase(env, `groups?id=eq.${device.group_id}`, {
      method: "DELETE",
      prefer: "return=minimal",
    });
  } else {
    await reconcileGroupPhotos(env, device.group_id);
  }

  return empty(204);
}

async function rotateInviteCode(request, env) {
  const device = await requireDevice(request, env);
  await enforceRateLimit(request, env, "ROTATE_LIMITER", "rotate", device.id);
  await requireAdmin(request, env, device);

  const group = await getGroup(env, device.group_id);
  const body = await readJson(request, { allowEmpty: true });
  const inviteCode = generateInviteCode();
  const patch = {
    code_hash: await hashInviteCode(env, inviteCode),
    rotated_at: new Date().toISOString(),
  };

  if (group?.crypto_mode === "e2ee") {
    const cryptoPayload = parseCryptoPayload(body.crypto);
    if (!cryptoPayload) throw new HttpError(400, "crypto_required");
    patch.key_salt = cryptoPayload.keySalt;
    patch.wrapped_key = cryptoPayload.wrappedKey;
    patch.key_iv = cryptoPayload.keyIv;
    patch.join_auth_hash = await hashJoinAuthKey(env, cryptoPayload.joinAuthKey);
  }

  await supabase(env, `groups?id=eq.${device.group_id}`, {
    method: "PATCH",
    body: patch,
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
  await cleanupExpiredPhotos(env);

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

async function cleanupExpiredPhotos(env) {
  const encodedNow = encodeURIComponent(new Date().toISOString());
  const photos = await supabase(
    env,
    `photos?expires_at=lt.${encodedNow}&select=id,object_path&limit=${MAX_PHOTOS_PER_CLEANUP}`,
  );
  for (const photo of photos || []) {
    try {
      // 404 is treated as success: the object is already gone.
      await storageDelete(env, photo.object_path);
    } catch (error) {
      console.error(
        "expired photo delete failed",
        photo.id,
        error?.message || error,
      );
      continue;
    }
    await supabase(env, `photos?id=eq.${photo.id}`, {
      method: "DELETE",
      prefer: "return=minimal",
    });
  }

  // Ownership records only exist to let a sender retract a photo. They carry
  // no image and are small, but prune them once they are no longer useful.
  // Revoked records stay for the full retention window counted from the
  // retraction, not from the original upload.
  const recordCutoff = encodeURIComponent(
    new Date(
      Date.now() - PHOTO_RECORD_RETENTION_DAYS * 24 * 60 * 60 * 1000,
    ).toISOString(),
  );
  await supabase(
    env,
    `photo_records?created_at=lt.${recordCutoff}&revoked_at=is.null`,
    {
      method: "DELETE",
      prefer: "return=minimal",
    },
  );
  await supabase(env, `photo_records?revoked_at=lt.${recordCutoff}`, {
    method: "DELETE",
    prefer: "return=minimal",
  });
}

async function readJson(request, { allowEmpty = false } = {}) {
  const declaredLength = Number(request.headers.get("content-length") || "0");
  if (!Number.isFinite(declaredLength) || declaredLength > MAX_BODY_BYTES) {
    throw new HttpError(413, "body_too_large");
  }
  const text = await request.text();
  if (text.length > MAX_BODY_BYTES) {
    throw new HttpError(413, "body_too_large");
  }
  if (allowEmpty && text.trim() === "") return {};
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

async function getGroup(env, groupId) {
  const groups = await supabase(
    env,
    `groups?id=eq.${groupId}&select=crypto_mode,key_salt,wrapped_key,key_iv,key_version,join_auth_hash`,
  );
  return groups?.[0] || null;
}

function cryptoResponse(group) {
  return {
    mode: "e2ee",
    keySalt: group.key_salt,
    wrappedKey: group.wrapped_key,
    keyIv: group.key_iv,
    keyVersion: group.key_version,
  };
}

/**
 * Validates the client-generated key material. The Worker cannot read any of
 * it; exact lengths keep junk data out of the database and the response.
 */
function parseCryptoPayload(raw) {
  if (raw === undefined || raw === null) return null;
  if (typeof raw !== "object" || Array.isArray(raw)) {
    throw new HttpError(400, "invalid_crypto");
  }
  const keySalt = cleanBase64Url(raw.keySalt, CRYPTO_KEY_SALT_LENGTH);
  const wrappedKey = cleanBase64Url(raw.wrappedKey, CRYPTO_WRAPPED_KEY_LENGTH);
  const keyIv = cleanBase64Url(raw.keyIv, CRYPTO_KEY_IV_LENGTH);
  // The phone sends the HKDF-derived join auth key, never the invite secret.
  // The Worker hashes it with the private pepper before storing it.
  const joinAuthKey = normalizeJoinAuthKey(raw.joinAuthHash);
  if (!keySalt || !wrappedKey || !keyIv || !joinAuthKey) {
    throw new HttpError(400, "invalid_crypto");
  }
  return { keySalt, wrappedKey, keyIv, joinAuthKey };
}

function cleanBase64Url(raw, expectedLength) {
  if (
    typeof raw !== "string" ||
    raw.length !== expectedLength ||
    !/^[A-Za-z0-9_-]+$/.test(raw)
  ) {
    return null;
  }
  return raw;
}

function normalizeJoinAuthKey(raw) {
  if (typeof raw !== "string") return null;
  const value = raw.trim();
  if (
    value.length !== CRYPTO_JOIN_AUTH_KEY_LENGTH ||
    !/^[A-Za-z0-9_-]+$/.test(value)
  ) {
    return null;
  }
  return value;
}

async function hashJoinAuthKey(env, authKey) {
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
    encoder.encode(`join:${authKey}`),
  );
  return toHex(signature);
}

function cleanEncryptedPayload(raw) {
  if (
    typeof raw !== "string" ||
    raw.length < 64 ||
    raw.length > MAX_ENCRYPTED_PAYLOAD ||
    !/^[A-Za-z0-9_-]+$/.test(raw)
  ) {
    return null;
  }
  return raw;
}

function cleanKeyVersion(raw) {
  if (!Number.isInteger(raw) || raw < 1 || raw > MAX_KEY_VERSION) return null;
  return raw;
}

async function readLimitedBytes(request, maxBytes) {
  const declared = Number(request.headers.get("content-length") || "0");
  if (Number.isFinite(declared) && declared > maxBytes) {
    throw new HttpError(413, "photo_too_large");
  }
  if (!request.body) return new Uint8Array(0);

  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel();
        throw new HttpError(413, "photo_too_large");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

function cleanPhotoId(raw) {
  if (typeof raw !== "string") return null;
  const value = raw.trim().toLowerCase();
  return PHOTO_ID_RE.test(value) ? value : null;
}

function cleanPhotoIv(raw) {
  if (typeof raw !== "string" || !PHOTO_IV_RE.test(raw)) return null;
  return raw;
}

function photoBucket(env) {
  const raw = typeof env?.PHOTO_BUCKET === "string" ? env.PHOTO_BUCKET.trim() : "";
  return /^[a-z0-9][a-z0-9-]{1,62}$/.test(raw) ? raw : DEFAULT_PHOTO_BUCKET;
}

function photoRetentionHours(env) {
  return clampInt(
    env?.PHOTO_RETENTION_HOURS,
    1,
    MAX_PHOTO_RETENTION_HOURS,
    DEFAULT_PHOTO_RETENTION_HOURS,
  );
}

/**
 * Emergency switch for new encrypted photo uploads. Set
 * PHOTO_UPLOAD_ENABLED=false in the Worker environment to stop accepting new
 * ciphertext without breaking family map sharing. Pending photos can still be
 * downloaded, acknowledged and deleted, so no upload is left stranded.
 */
function photoUploadEnabled(env) {
  const raw = env?.PHOTO_UPLOAD_ENABLED;
  if (typeof raw !== "string") return true;
  return !["0", "false", "no", "off", "disabled"].includes(
    raw.trim().toLowerCase(),
  );
}

function storageObjectUrl(env, path) {
  if (!env.SUPABASE_URL || !env.SUPABASE_SERVICE_ROLE_KEY) {
    throw new HttpError(500, "server_error");
  }
  const base = env.SUPABASE_URL.replace(/\/$/, "");
  const bucket = encodeURIComponent(photoBucket(env));
  const object = String(path)
    .split("/")
    .map(encodeURIComponent)
    .join("/");
  return `${base}/storage/v1/object/${bucket}/${object}`;
}

function storageHeaders(env, extra = {}) {
  return {
    apikey: env.SUPABASE_SERVICE_ROLE_KEY,
    authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
    ...extra,
  };
}

async function storageUpload(env, path, bytes) {
  const response = await fetch(storageObjectUrl(env, path), {
    method: "POST",
    headers: storageHeaders(env, {
      "content-type": "application/octet-stream",
      "x-upsert": "false",
    }),
    body: bytes,
  });
  if (!response.ok) {
    const detail = await response.text();
    console.error(
      "storage upload failed",
      response.status,
      detail.slice(0, 300),
    );
    throw new HttpError(500, "server_error");
  }
}

async function storageDownload(env, path) {
  const response = await fetch(storageObjectUrl(env, path), {
    headers: storageHeaders(env),
  });
  if (response.ok) return response;
  if (response.status === 404) throw new HttpError(404, "not_found");
  const detail = await response.text();
  console.error(
    "storage download failed",
    response.status,
    detail.slice(0, 300),
  );
  throw new HttpError(500, "server_error");
}

async function storageDelete(env, path) {
  const response = await fetch(storageObjectUrl(env, path), {
    method: "DELETE",
    headers: storageHeaders(env),
  });
  // A missing object is already in the desired state.
  if (response.ok || response.status === 404) return;
  const detail = await response.text();
  console.error(
    "storage delete failed",
    response.status,
    detail.slice(0, 300),
  );
  throw new HttpError(500, "server_error");
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
  DEFAULT_PHOTO_BUCKET,
  MAX_GROUP_MEMBERS,
  MAX_PHOTO_BYTES,
  cleanBase64Url,
  cleanDisplayName,
  cleanEncryptedPayload,
  cleanKeyVersion,
  cleanPhotoId,
  cleanPhotoIv,
  clientTimeOrNow,
  cryptoResponse,
  generateInviteCode,
  hashInviteCode,
  hashJoinAuthKey,
  inRange,
  normalizeInviteCode,
  normalizeJoinAuthKey,
  parseCryptoPayload,
  photoBucket,
  photoRetentionHours,
  photoUploadEnabled,
  randomToken,
  sha256Hex,
  timingSafeEqual,
  toHex,
};
