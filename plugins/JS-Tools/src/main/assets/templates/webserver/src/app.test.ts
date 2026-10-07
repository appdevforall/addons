import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { handle } from './app.ts';

test('greets as JSON', async () => {
  const server = createServer(handle);
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const { port } = server.address() as AddressInfo;
    const response = await fetch(`http://127.0.0.1:${port}/api/hello?name=Ada`);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { message: 'Hello, Ada!' });
  } finally {
    server.close();
  }
});

test('answers unknown paths with 404', async () => {
  const server = createServer(handle);
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const { port } = server.address() as AddressInfo;
    const response = await fetch(`http://127.0.0.1:${port}/missing`);
    assert.equal(response.status, 404);
  } finally {
    server.close();
  }
});
