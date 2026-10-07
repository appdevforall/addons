<?php

declare(strict_types=1);

require __DIR__ . '/../src/App.php';

use App\App;

$response = App::handle(
    $_SERVER['REQUEST_METHOD'] ?? 'GET',
    parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/',
    $_GET,
);

http_response_code($response['status']);
header('Content-Type: ' . $response['type']);
echo $response['body'];
