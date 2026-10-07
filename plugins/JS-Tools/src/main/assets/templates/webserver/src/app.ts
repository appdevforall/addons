import type { IncomingMessage, ServerResponse } from 'node:http';

export function handle(request: IncomingMessage, response: ServerResponse): void {
  const url = new URL(request.url ?? '/', 'http://localhost');
  if (request.method === 'GET' && url.pathname === '/') {
    send(response, 200, 'text/plain; charset=utf-8', 'Hello from Code on the Go!\n');
  } else if (request.method === 'GET' && url.pathname === '/api/hello') {
    const name = url.searchParams.get('name') ?? 'world';
    send(response, 200, 'application/json', JSON.stringify({ message: `Hello, ${name}!` }));
  } else {
    send(response, 404, 'application/json', JSON.stringify({ error: 'Not found' }));
  }
}

function send(response: ServerResponse, status: number, type: string, body: string): void {
  response.writeHead(status, { 'Content-Type': type });
  response.end(body);
}
