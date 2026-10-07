<?php

declare(strict_types=1);

require __DIR__ . '/../src/Greeter.php';

use App\Greeter;

$greeter = new Greeter();

assert($greeter->greet('Ada') === 'Hello, Ada!');
echo "ok - greets by name\n";

assert($greeter->greet('') === 'Hello, !');
echo "ok - keeps an empty name\n";
