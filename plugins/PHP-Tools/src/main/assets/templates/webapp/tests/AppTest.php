<?php

declare(strict_types=1);

require __DIR__ . '/../src/App.php';

use App\App;

$home = App::handle('GET', '/', []);
assert($home['status'] === 200);
assert(str_contains($home['body'], 'Hello from Code on the Go!'));
echo "ok - serves the home page\n";

$hello = App::handle('GET', '/api/hello', ['name' => 'Ada']);
assert($hello['status'] === 200);
assert(json_decode($hello['body'], true) === ['message' => 'Hello, Ada!']);
echo "ok - greets as JSON\n";

$missing = App::handle('GET', '/missing', []);
assert($missing['status'] === 404);
echo "ok - answers unknown paths with 404\n";
