<?php

class Auth {
    public static function requireDevice(PDO $db): array {
        $header = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
        
        // Fallback for CGI/FastCGI where Authorization header may not be in $_SERVER
        if (empty($header) && function_exists('getallheaders')) {
            $headers = getallheaders();
            $header = $headers['Authorization'] ?? '';
        }
        
        // Another fallback for Apache mod_rewrite environments
        if (empty($header)) {
            $header = $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
        }
        
        // Compatible with PHP 7.4+
        if (strncmp($header, 'Bearer ', 7) !== 0) {
            http_response_code(401);
            echo json_encode(['error' => 'missing_token']);
            exit;
        }

        $token     = substr($header, 7);
        $tokenHash = hash('sha256', $token);

        $stmt = $db->prepare("SELECT * FROM devices WHERE token_hash = ?");
        $stmt->execute([$tokenHash]);
        $device = $stmt->fetch();

        if (!$device) {
            http_response_code(401);
            echo json_encode(['error' => 'invalid_token']);
            exit;
        }

        $db->prepare("UPDATE devices SET last_active = ? WHERE id = ?")
           ->execute([time(), $device['id']]);

        return $device;
    }

    public static function generateToken(): string {
        return bin2hex(random_bytes(32));
    }
}
