const encoder = new TextEncoder();

export class AuthError extends Error {
  constructor(status = 401) { super('unauthorized'); this.status = status; }
}

function bytes(value) {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new AuthError();
  return Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
}

function equal(a, b) {
  let difference = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) difference |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  return difference === 0;
}

export async function tokenHash(token) {
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(token))), b => b.toString(16).padStart(2, '0')).join('');
}

// The fetch target comes only from trusted configuration, never JWT iss/jku/x5u.
export function createAuthenticator({ fetch: fetchKeys = (...args) => globalThis.fetch(...args), now = Date.now } = {}) {
  const caches = new Map();
  async function signingKey(team, kid) {
    let cache = caches.get(team);
    if (!cache) { cache = { keys: [], expires: 0, fetched: -Infinity, pending: null }; caches.set(team, cache); }
    const lookup = () => cache.keys.find(key => key.kid === kid && key.kty === 'RSA' && (!key.alg || key.alg === 'RS256') && (!key.use || key.use === 'sig'));
    const expired = now() >= cache.expires;
    if (expired || (!lookup() && now() - cache.fetched >= 30_000)) {
      if (!cache.pending && now() - cache.fetched < 30_000) throw new AuthError();
      if (!cache.pending) {
        cache.fetched = now();
        cache.pending = (async () => {
          // Workers supports manual/follow, not redirect:'error'. Never follow a
          // JWKS redirect: response.ok below rejects every redirect status.
          const response = await fetchKeys(`https://${team}.cloudflareaccess.com/cdn-cgi/access/certs`, { redirect: 'manual', signal: AbortSignal.timeout(5000) });
          if (!response.ok) throw new AuthError();
          const body = await response.json();
          if (!Array.isArray(body.keys) || body.keys.length > 20) throw new AuthError();
          cache.keys = body.keys;
          cache.expires = now() + 300_000;
        })().finally(() => { cache.pending = null; });
      }
      await cache.pending;
    }
    const jwk = lookup();
    if (!jwk || now() >= cache.expires) throw new AuthError();
    return crypto.subtle.importKey('jwk', jwk, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
  }

  return async function authenticate(request, env, mode) {
    try {
      if (mode === 'mcp') {
        let devices;
        try { devices = JSON.parse(env.MACHINE_TOKEN_HASHES); } catch { throw new AuthError(503); }
        if (!devices || Array.isArray(devices) || typeof devices !== 'object' || !Object.keys(devices).length
            || Object.entries(devices).some(([id, hash]) => !/^[A-Za-z0-9_-]{1,64}$/.test(id) || typeof hash !== 'string' || !/^[a-f0-9]{64}$/.test(hash))) throw new AuthError(503);
        const authorization = request.headers.get('Authorization') || '';
        const match = /^Bearer ([A-Za-z0-9_-]{43,512})$/.exec(authorization);
        if (!match) throw new AuthError();
        const digest = await tokenHash(match[1]);
        let deviceId;
        for (const [id, hash] of Object.entries(devices)) if (equal(digest, hash)) deviceId = id;
        if (!deviceId) throw new AuthError();
        return { deviceId };
      }
      const team = env.CLOUDFLARE_ACCESS_TEAM;
      if (typeof team !== 'string' || !/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(team)
          || typeof env.CLOUDFLARE_ACCESS_AUD !== 'string' || !env.CLOUDFLARE_ACCESS_AUD.trim()
          || typeof env.OWNER_EMAIL !== 'string' || !env.OWNER_EMAIL.includes('@')) throw new AuthError(503);
      const jwt = request.headers.get('Cf-Access-Jwt-Assertion') || '';
      if (jwt.length > 16384) throw new AuthError();
      const parts = jwt.split('.');
      if (parts.length !== 3) throw new AuthError();
      const header = JSON.parse(new TextDecoder().decode(bytes(parts[0])));
      const payload = JSON.parse(new TextDecoder().decode(bytes(parts[1])));
      if (header.alg !== 'RS256' || typeof header.kid !== 'string' || !header.kid || header.kid.length > 256 || header.crit) throw new AuthError();
      const issuer = `https://${team}.cloudflareaccess.com`;
      const audience = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
      const seconds = now() / 1000;
      if (payload.iss !== issuer || !audience.includes(env.CLOUDFLARE_ACCESS_AUD)
          || typeof payload.exp !== 'number' || !Number.isFinite(payload.exp) || payload.exp <= seconds
          || (payload.nbf !== undefined && (typeof payload.nbf !== 'number' || !Number.isFinite(payload.nbf) || payload.nbf > seconds))
          || typeof payload.email !== 'string' || payload.email.toLowerCase() !== env.OWNER_EMAIL.toLowerCase()) throw new AuthError();
      const key = await signingKey(team, header.kid);
      if (!await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, bytes(parts[2]), encoder.encode(parts[0] + '.' + parts[1]))) throw new AuthError();
      return { email: payload.email };
    } catch (error) {
      if (error instanceof AuthError) throw error;
      throw new AuthError();
    }
  };
}

export const authenticate = createAuthenticator();
