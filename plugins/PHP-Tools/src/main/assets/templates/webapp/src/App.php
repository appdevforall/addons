<?php

declare(strict_types=1);

namespace App;

final class App
{
    public static function handle(string $method, string $path, array $query): array
    {
        if ($method === 'GET' && $path === '/') {
            return self::html(200, self::page());
        }
        if ($method === 'GET' && $path === '/api/hello') {
            $name = is_string($query['name'] ?? null) && $query['name'] !== '' ? $query['name'] : 'world';
            return self::json(200, ['message' => "Hello, {$name}!"]);
        }
        return self::json(404, ['error' => 'Not found']);
    }

    private static function html(int $status, string $body): array
    {
        return ['status' => $status, 'type' => 'text/html; charset=utf-8', 'body' => $body];
    }

    private static function json(int $status, array $data): array
    {
        return ['status' => $status, 'type' => 'application/json', 'body' => json_encode($data, JSON_THROW_ON_ERROR)];
    }

    private static function page(): string
    {
        return <<<HTML
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Hello from Code on the Go</title>
            <link rel="stylesheet" href="/style.css">
            </head>
            <body>
            <main>
            <h1>Hello from Code on the Go!</h1>
            <p>PHP's built-in web server is serving this page from your device.</p>
            <p>Try the JSON API: <a href="/api/hello?name=Ada">/api/hello?name=Ada</a></p>
            </main>
            </body>
            </html>
            HTML;
    }
}
