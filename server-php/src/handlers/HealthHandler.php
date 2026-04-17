<?php

class HealthHandler {
    public static function handle(array $params): void {
        echo json_encode([
            'status' => 'ok',
            'time'   => time(),
        ]);
    }
}
