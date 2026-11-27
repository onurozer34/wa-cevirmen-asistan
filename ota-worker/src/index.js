export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return new Response('Method Not Allowed', { status: 405 });
    }

    if (url.pathname === '/' || url.pathname === '') {
      const target = new URL('/version.json', url.origin);
      const assetRes = await env.ASSETS.fetch(new Request(target, request));
      const headers = new Headers(assetRes.headers);
      headers.set('Content-Type', 'application/json; charset=utf-8');
      headers.set('Cache-Control', 'no-store, max-age=0');
      headers.set('Access-Control-Allow-Origin', '*');
      return new Response(request.method === 'HEAD' ? null : assetRes.body, {
        status: assetRes.status,
        headers,
      });
    }

    const assetRes = await env.ASSETS.fetch(request);
    if (assetRes.status === 404) {
      return new Response('Not Found', { status: 404 });
    }

    const headers = new Headers(assetRes.headers);
    if (url.pathname.endsWith('.json')) {
      headers.set('Content-Type', 'application/json; charset=utf-8');
      headers.set('Cache-Control', 'no-store, max-age=0');
      headers.set('Access-Control-Allow-Origin', '*');
    } else if (url.pathname.endsWith('.apk')) {
      headers.set('Content-Type', 'application/vnd.android.package-archive');
      headers.set('Content-Disposition', 'attachment; filename="WA-Cevirmen-Asistan.apk"');
      headers.set('Cache-Control', 'no-store, max-age=0');
    }

    return new Response(request.method === 'HEAD' ? null : assetRes.body, {
      status: assetRes.status,
      headers,
    });
  },
};
