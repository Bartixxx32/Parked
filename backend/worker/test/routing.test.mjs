import assert from "node:assert/strict";
import test from "node:test";

import worker from "../src/index.js";

function request(path, init = {}) {
  return new Request(`https://parked.example${path}`, init);
}

test("health check returns JSON with security headers and no CORS", async () => {
  const response = await worker.fetch(request("/v1/health"), {});
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true, service: "parked" });
  assert.equal(response.headers.get("access-control-allow-origin"), null);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(response.headers.get("x-content-type-options"), "nosniff");
  assert.equal(response.headers.get("x-frame-options"), "DENY");
  assert.equal(response.headers.get("referrer-policy"), "no-referrer");
});

test("browser preflight is not supported", async () => {
  const response = await worker.fetch(
    request("/v1/groups", { method: "OPTIONS" }),
    {},
  );
  assert.equal(response.status, 404);
  assert.equal(response.headers.get("access-control-allow-origin"), null);
  assert.deepEqual(await response.json(), { error: "not_found" });
});

test("unknown routes use the same security headers", async () => {
  const response = await worker.fetch(request("/nope"), {});
  assert.equal(response.status, 404);
  assert.equal(response.headers.get("x-content-type-options"), "nosniff");
  assert.equal(response.headers.get("access-control-allow-origin"), null);
});

test("device routes reject a missing bearer token", async () => {
  const photoId = "123e4567-e89b-42d3-a456-426614174000";
  const responses = await Promise.all([
    worker.fetch(request("/v1/spots"), {}),
    worker.fetch(request("/v1/spot", { method: "DELETE" }), {}),
    worker.fetch(request("/v1/photos"), {}),
    worker.fetch(
      request("/v1/photos", {
        method: "POST",
        headers: { "x-photo-id": photoId },
        body: new Uint8Array(32),
      }),
      {},
    ),
    worker.fetch(request(`/v1/photos/${photoId}`), {}),
    worker.fetch(request(`/v1/photos/${photoId}/ack`, { method: "POST" }), {}),
    worker.fetch(
      request("/v1/group", {
        method: "PATCH",
        body: JSON.stringify({ name: "Family" }),
      }),
      {},
    ),
  ]);
  for (const response of responses) {
    assert.equal(response.status, 401);
    assert.deepEqual(await response.json(), { error: "unauthorized" });
  }
});

test("malformed JSON is rejected before Supabase", async () => {
  const response = await worker.fetch(
    request("/v1/groups", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "{",
    }),
    {},
  );
  assert.equal(response.status, 400);
  assert.deepEqual(await response.json(), { error: "invalid_json" });
});

test("oversized bodies are rejected", async () => {
  const response = await worker.fetch(
    request("/v1/groups", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: `{"displayName":"${"x".repeat(5000)}"}`,
    }),
    {},
  );
  assert.equal(response.status, 413);
  assert.deepEqual(await response.json(), { error: "body_too_large" });
});

test("an empty car label is rejected before Supabase", async () => {
  const response = await worker.fetch(
    request("/v1/groups", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ displayName: "   " }),
    }),
    {},
  );
  assert.equal(response.status, 400);
  assert.deepEqual(await response.json(), { error: "invalid_name" });
});

test("photo uploads can be disabled without breaking the rest of the API", async () => {
  const originalFetch = globalThis.fetch;
  const device = { id: "11111111-1111-4111-8111-111111111111", group_id: "22222222-2222-4222-8222-222222222222" };
  globalThis.fetch = async (url) => {
    const target = String(url);
    if (target.includes("/rest/v1/devices?")) {
      return new Response(JSON.stringify([device]), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    if (target.includes("/rest/v1/photo_deliveries?")) {
      return new Response(JSON.stringify([]), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    if (target.includes("/rest/v1/photo_records?")) {
      return new Response(JSON.stringify([]), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    throw new Error(`unexpected fetch: ${target}`);
  };
  const env = {
    SUPABASE_URL: "https://supabase.example",
    SUPABASE_SERVICE_ROLE_KEY: "service-role",
    PHOTO_UPLOAD_ENABLED: "false",
  };
  const auth = "Bearer " + "a".repeat(43);
  try {
    const upload = await worker.fetch(
      request("/v1/photos", {
        method: "POST",
        headers: {
          authorization: auth,
          "x-photo-id": "123e4567-e89b-42d3-a456-426614174000",
          "x-photo-iv": "AAAAAAAAAAAAAAAA",
          "x-key-version": "1",
        },
        body: new Uint8Array(32),
      }),
      env,
    );
    assert.equal(upload.status, 503);
    assert.deepEqual(await upload.json(), { error: "photos_disabled" });

    const list = await worker.fetch(
      request("/v1/photos", { headers: { authorization: auth } }),
      env,
    );
    assert.equal(list.status, 200);
    assert.deepEqual(await list.json(), {
      photos: [],
      uploadEnabled: false,
      revokedPhotoIds: [],
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("photo listing reports retracted ids to family phones", async () => {
  const originalFetch = globalThis.fetch;
  const device = { id: "11111111-1111-4111-8111-111111111111", group_id: "22222222-2222-4222-8222-222222222222" };
  const revokedId = "33333333-3333-4333-8333-333333333333";
  globalThis.fetch = async (url) => {
    const target = String(url);
    if (target.includes("/rest/v1/devices?")) {
      return new Response(JSON.stringify([device]), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    if (target.includes("/rest/v1/photo_deliveries?")) {
      return new Response(JSON.stringify([]), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    if (target.includes("/rest/v1/photo_records?")) {
      return new Response(JSON.stringify([{ photo_id: revokedId }]), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    throw new Error(`unexpected fetch: ${target}`);
  };
  try {
    const response = await worker.fetch(
      request("/v1/photos", {
        headers: { authorization: "Bearer " + "a".repeat(43) },
      }),
      {
        SUPABASE_URL: "https://supabase.example",
        SUPABASE_SERVICE_ROLE_KEY: "service-role",
      },
    );
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {
      photos: [],
      uploadEnabled: true,
      revokedPhotoIds: [revokedId],
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("a sender can retract a delivered photo through its ownership record", async () => {
  const originalFetch = globalThis.fetch;
  const device = { id: "11111111-1111-4111-8111-111111111111", group_id: "22222222-2222-4222-8222-222222222222" };
  const photoId = "33333333-3333-4333-8333-333333333333";
  let revokedPatch = null;
  globalThis.fetch = async (url, init = {}) => {
    const target = String(url);
    if (target.includes("/rest/v1/devices?")) {
      return new Response(JSON.stringify([device]), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    if (target.includes(`/rest/v1/photos?`)) {
      return new Response(JSON.stringify([]), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    if (target.includes(`/rest/v1/photo_records?photo_id=eq.${photoId}`)) {
      if (init.method === "PATCH") {
        revokedPatch = JSON.parse(init.body);
        return new Response(null, { status: 204 });
      }
      return new Response(
        JSON.stringify([{ photo_id: photoId, sender_device_id: device.id }]),
        { status: 200, headers: { "content-type": "application/json" } },
      );
    }
    throw new Error(`unexpected fetch: ${target}`);
  };
  try {
    const response = await worker.fetch(
      request(`/v1/photos/${photoId}`, {
        method: "DELETE",
        headers: { authorization: "Bearer " + "a".repeat(43) },
      }),
      {
        SUPABASE_URL: "https://supabase.example",
        SUPABASE_SERVICE_ROLE_KEY: "service-role",
      },
    );
    assert.equal(response.status, 204);
    assert.ok(revokedPatch?.revoked_at);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("another family member cannot retract someone else's photo", async () => {
  const originalFetch = globalThis.fetch;
  const device = { id: "11111111-1111-4111-8111-111111111111", group_id: "22222222-2222-4222-8222-222222222222" };
  const photoId = "33333333-3333-4333-8333-333333333333";
  let patched = false;
  globalThis.fetch = async (url, init = {}) => {
    const target = String(url);
    if (target.includes("/rest/v1/devices?")) {
      return new Response(JSON.stringify([device]), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    if (target.includes(`/rest/v1/photos?`)) {
      return new Response(JSON.stringify([]), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    if (target.includes(`/rest/v1/photo_records?photo_id=eq.${photoId}`)) {
      if (init.method === "PATCH") {
        patched = true;
        return new Response(null, { status: 204 });
      }
      return new Response(
        JSON.stringify([
          {
            photo_id: photoId,
            sender_device_id: "44444444-4444-4444-8444-444444444444",
          },
        ]),
        { status: 200, headers: { "content-type": "application/json" } },
      );
    }
    throw new Error(`unexpected fetch: ${target}`);
  };
  try {
    const response = await worker.fetch(
      request(`/v1/photos/${photoId}`, {
        method: "DELETE",
        headers: { authorization: "Bearer " + "a".repeat(43) },
      }),
      {
        SUPABASE_URL: "https://supabase.example",
        SUPABASE_SERVICE_ROLE_KEY: "service-role",
      },
    );
    assert.equal(response.status, 403);
    assert.deepEqual(await response.json(), { error: "forbidden" });
    assert.equal(patched, false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("a member can rename a plaintext legacy group", async () => {
  const originalFetch = globalThis.fetch;
  const device = { id: "11111111-1111-4111-8111-111111111111", group_id: "22222222-2222-4222-8222-222222222222" };
  let patchBody = null;
  globalThis.fetch = async (url, init = {}) => {
    const target = String(url);
    if (target.includes("/rest/v1/devices?")) {
      return new Response(JSON.stringify([device]), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    if (target.includes("/rest/v1/groups?")) {
      if (init.method === "PATCH") {
        patchBody = JSON.parse(init.body);
        return new Response(null, { status: 204 });
      }
      return new Response(
        JSON.stringify([
          {
            crypto_mode: "plaintext",
            key_version: 1,
            name: null,
            name_payload: null,
            name_key_version: null,
          },
        ]),
        { status: 200, headers: { "content-type": "application/json" } },
      );
    }
    throw new Error(`unexpected fetch: ${target}`);
  };
  try {
    const response = await worker.fetch(
      request("/v1/group", {
        method: "PATCH",
        headers: { authorization: "Bearer " + "a".repeat(43) },
        body: JSON.stringify({ name: "  Rodzina Kowalskich  " }),
      }),
      {
        SUPABASE_URL: "https://supabase.example",
        SUPABASE_SERVICE_ROLE_KEY: "service-role",
      },
    );
    assert.equal(response.status, 204);
    assert.deepEqual(patchBody, {
      name: "Rodzina Kowalskich",
      name_payload: null,
      name_key_version: null,
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("spot listing includes the shared group name", async () => {
  const originalFetch = globalThis.fetch;
  const device = { id: "11111111-1111-4111-8111-111111111111", group_id: "22222222-2222-4222-8222-222222222222" };
  globalThis.fetch = async (url, init = {}) => {
    const target = String(url);
    if (target.includes("/rest/v1/devices?")) {
      if (init.method === "PATCH") return new Response(null, { status: 204 });
      if (target.includes("group_id=eq.")) {
        return new Response(
          JSON.stringify([
            {
              id: device.id,
              display_name: "Golf",
              last_seen_at: "2026-11-20T10:00:00Z",
            },
          ]),
          { status: 200, headers: { "content-type": "application/json" } },
        );
      }
      return new Response(JSON.stringify([device]), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    if (target.includes("/rest/v1/groups?")) {
      return new Response(
        JSON.stringify([
          {
            crypto_mode: "plaintext",
            name: "Rodzina Kowalskich",
            name_payload: null,
            name_key_version: null,
          },
        ]),
        { status: 200, headers: { "content-type": "application/json" } },
      );
    }
    if (target.includes("/rest/v1/spots?")) {
      return new Response(JSON.stringify([]), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    throw new Error(`unexpected fetch: ${target}`);
  };
  try {
    const response = await worker.fetch(
      request("/v1/spots", {
        headers: { authorization: "Bearer " + "a".repeat(43) },
      }),
      {
        SUPABASE_URL: "https://supabase.example",
        SUPABASE_SERVICE_ROLE_KEY: "service-role",
      },
    );
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.groupName, "Rodzina Kowalskich");
    assert.equal(body.cryptoMode, "plaintext");
    assert.equal(body.groupNamePayload, undefined);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("an E2EE group accepts only an encrypted name", async () => {
  const originalFetch = globalThis.fetch;
  const device = { id: "11111111-1111-4111-8111-111111111111", group_id: "22222222-2222-4222-8222-222222222222" };
  const payload = "A".repeat(64);
  let patchBody = null;
  globalThis.fetch = async (url, init = {}) => {
    const target = String(url);
    if (target.includes("/rest/v1/devices?")) {
      return new Response(JSON.stringify([device]), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    if (target.includes("/rest/v1/groups?")) {
      if (init.method === "PATCH") {
        patchBody = JSON.parse(init.body);
        return new Response(null, { status: 204 });
      }
      return new Response(
        JSON.stringify([
          {
            crypto_mode: "e2ee",
            key_version: 1,
            name: null,
            name_payload: null,
            name_key_version: null,
          },
        ]),
        { status: 200, headers: { "content-type": "application/json" } },
      );
    }
    throw new Error(`unexpected fetch: ${target}`);
  };
  const env = {
    SUPABASE_URL: "https://supabase.example",
    SUPABASE_SERVICE_ROLE_KEY: "service-role",
  };
  const auth = "Bearer " + "a".repeat(43);
  try {
    const plaintext = await worker.fetch(
      request("/v1/group", {
        method: "PATCH",
        headers: { authorization: auth },
        body: JSON.stringify({ name: "Rodzina" }),
      }),
      env,
    );
    assert.equal(plaintext.status, 400);
    assert.deepEqual(await plaintext.json(), { error: "crypto_required" });
    assert.equal(patchBody, null);

    const encrypted = await worker.fetch(
      request("/v1/group", {
        method: "PATCH",
        headers: { authorization: auth },
        body: JSON.stringify({ namePayload: payload, keyVersion: 1 }),
      }),
      env,
    );
    assert.equal(encrypted.status, 204);
    assert.deepEqual(patchBody, {
      name: null,
      name_payload: payload,
      name_key_version: 1,
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("leaving a group retracts the member's photo records", async () => {
  const originalFetch = globalThis.fetch;
  const device = { id: "11111111-1111-4111-8111-111111111111", group_id: "22222222-2222-4222-8222-222222222222" };
  let revokedPatch = null;
  globalThis.fetch = async (url, init = {}) => {
    const target = String(url);
    if (target.includes("/rest/v1/devices?")) {
      if (init.method === "DELETE") return new Response(null, { status: 204 });
      if (target.includes("id=neq.")) {
        return new Response(JSON.stringify([{ id: "other" }]), {
          status: 200,
          headers: { "content-type": "application/json" },
        });
      }
      return new Response(JSON.stringify([device]), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    if (target.includes("/rest/v1/photo_records?")) {
      revokedPatch = JSON.parse(init.body);
      return new Response(null, { status: 204 });
    }
    if (target.includes("/rest/v1/photos?")) {
      return new Response(JSON.stringify([]), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    throw new Error(`unexpected fetch: ${target}`);
  };
  try {
    const response = await worker.fetch(
      request("/v1/device", {
        method: "DELETE",
        headers: { authorization: "Bearer " + "a".repeat(43) },
      }),
      {
        SUPABASE_URL: "https://supabase.example",
        SUPABASE_SERVICE_ROLE_KEY: "service-role",
      },
    );
    assert.equal(response.status, 204);
    assert.ok(revokedPatch?.revoked_at);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("rate limited requests return 429 and Retry-After", async () => {
  const env = {
    GLOBAL_LIMITER: {
      async limit() {
        return { success: false };
      },
    },
  };
  const response = await worker.fetch(
    request("/v1/groups", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ displayName: "Car" }),
    }),
    env,
  );
  assert.equal(response.status, 429);
  assert.equal(response.headers.get("retry-after"), "60");
  assert.deepEqual(await response.json(), { error: "rate_limited" });
});
