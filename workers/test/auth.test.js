import test from 'node:test';
import assert from 'node:assert/strict';
import { createAuthenticator, tokenHash } from '../src/auth.js';

const pair = await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify']);
const jwk = { ...await crypto.subtle.exportKey('jwk', pair.publicKey), kid: 'current' };
const currentTime = 1_800_000_000_000;
const env = { CLOUDFLARE_ACCESS_TEAM: 'test-team', CLOUDFLARE_ACCESS_AUD: 'board-audience', OWNER_EMAIL: 'owner@example.com' };
const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
async function jwt(claims = {}, header = {}) {
  const payload = { iss: 'https://test-team.cloudflareaccess.com', aud: ['board-audience'], email: 'owner@example.com', exp: currentTime / 1000 + 300, nbf: currentTime / 1000 - 10, ...claims };
  const unsigned = `${encode({ alg: 'RS256', kid: 'current', ...header })}.${encode(payload)}`;
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', pair.privateKey, new TextEncoder().encode(unsigned));
  return `${unsigned}.${Buffer.from(signature).toString('base64url')}`;
}
const request = token => new Request('https://board.example.com/', { headers: { 'Cf-Access-Jwt-Assertion': token } });

test('Access validates RSA signature and configured issuer/audience/time/owner; fixed JWKS cache', async () => {
  let calls = 0;
  const auth = createAuthenticator({ now: () => currentTime, fetch: async (url, options) => {
    assert.equal(url, 'https://test-team.cloudflareaccess.com/cdn-cgi/access/certs');
    assert.equal(options.redirect, 'manual');
    calls++;
    return Response.json({ keys: [jwk] });
  } });
  const identity = await auth(request(await jwt()), env, 'web');
  assert.equal(identity.email, env.OWNER_EMAIL);
  await auth(request(await jwt()), env, 'web');
  assert.equal(calls, 1);
  for (const claims of [
    { iss: 'https://attacker.example.com' }, { aud: ['another-app'] },
    { email: 'other@example.com' }, { exp: currentTime / 1000 },
    { nbf: currentTime / 1000 + 100 }, { exp: 'tomorrow' },
  ]) await assert.rejects(() => jwt(claims).then(token => auth(request(token), env, 'web')), { message: 'unauthorized' });
  const token = await jwt();
  const segments = token.split('.');
  segments[2] = (segments[2][0] === 'A' ? 'B' : 'A') + segments[2].slice(1);
  await assert.rejects(() => auth(request(segments.join('.')), env, 'web'), { message: 'unauthorized' });
  await assert.rejects(() => auth(request(encode({ alg: 'none' }) + '.' + encode({}) + '.x'), env, 'web'));
  assert.equal(calls, 1);
});

test('Access never follows a JWKS redirect to a different origin', async () => {
  let calls = 0;
  const auth = createAuthenticator({ now: () => currentTime, fetch: async (_url, options) => {
    calls++;
    assert.equal(options.redirect, 'manual');
    return new Response(null, { status: 302, headers: { Location: 'https://attacker.example.test/keys' } });
  } });
  await assert.rejects(() => jwt().then(token => auth(request(token), env, 'web')));
  assert.equal(calls, 1);
});

test('Access fails closed without config or when fetch fails; unknown key refresh is bounded', async () => {
  let clock = currentTime, calls = 0;
  const auth = createAuthenticator({ now: () => clock, fetch: async () => { calls++; return Response.json({ keys: [jwk] }); } });
  await assert.rejects(() => auth(request('forged'), {}, 'web'), { status: 503 });
  await auth(request(await jwt()), env, 'web');
  await assert.rejects(() => jwt({}, { kid: 'unknown' }).then(token => auth(request(token), env, 'web')));
  assert.equal(calls, 1);
  clock += 31_000;
  await assert.rejects(() => jwt({}, { kid: 'unknown' }).then(token => auth(request(token), env, 'web')));
  await assert.rejects(() => jwt({}, { kid: 'another-unknown' }).then(token => auth(request(token), env, 'web')));
  assert.equal(calls, 2);
  let failures = 0;
  const unavailable = createAuthenticator({ now: () => currentTime, fetch: async () => { failures++; throw new Error('upstream unavailable'); } });
  await assert.rejects(() => jwt().then(token => unavailable(request(token), env, 'web')));
  await assert.rejects(() => jwt().then(token => unavailable(request(token), env, 'web')));
  assert.equal(failures, 1);
});

test('machine bearer hashes identify devices, rotate/revoke immediately, and never use Access headers', async () => {
  const token = Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString('base64url');
  const second = Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString('base64url');
  const auth = createAuthenticator();
  const machineEnv = { MACHINE_TOKEN_HASHES: JSON.stringify({ laptop: await tokenHash(token), desktop: await tokenHash(second) }) };
  const machine = value => new Request('https://mcp.example.com/mcp', { headers: { Authorization: `Bearer ${value}` } });
  assert.equal((await auth(machine(token), machineEnv, 'mcp')).deviceId, 'laptop');
  assert.equal((await auth(machine(second), machineEnv, 'mcp')).deviceId, 'desktop');
  const revoked = { MACHINE_TOKEN_HASHES: JSON.stringify({ desktop: await tokenHash(second) }) };
  await assert.rejects(() => auth(machine(token), revoked, 'mcp'));
  await assert.rejects(() => auth(machine('short'), machineEnv, 'mcp'));
  await assert.rejects(async () => auth(request(await jwt()), machineEnv, 'mcp'));
  await assert.rejects(() => auth(machine(token), {}, 'mcp'), { status: 503 });
  await assert.rejects(() => auth(machine(token), { MACHINE_TOKEN_HASHES: '{"device":"invalid"}' }, 'mcp'), { status: 503 });
});
