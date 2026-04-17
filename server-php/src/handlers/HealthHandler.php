<?php

class HealthHandler {
    public static function handle(array $params): void {
        echo json_encode([
            'status'  => 'ok',
            'version' => '1.0.0',
            'time'    => time(),
        ]);
    }
}
