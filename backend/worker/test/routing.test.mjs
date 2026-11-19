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
  const responses = await Promise.all([
    worker.fetch(request("/v1/spots"), {}),
    worker.fetch(request("/v1/spot", { method: "DELETE" }), {}),
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
