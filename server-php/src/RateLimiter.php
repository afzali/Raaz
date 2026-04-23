<?php

/**
 * Per-IP rate limiter with dual-window support (burst + daily).
 * Limits are loaded from config.php so they're configurable without touching code.
 * Stored in the same SQLite DB — no extra infrastructure needed.
 */
class RateLimiter {

    public static function init(PDO $db): void {
        $db->exec("CREATE TABLE IF NOT EXISTS rate_limit (
            ip           TEXT NOT NULL,
            bucket       TEXT NOT NULL,
            hits         INTEGER NOT NULL DEFAULT 1,
            window_start INTEGER NOT NULL,
            PRIMARY KEY (ip, bucket)
        )");
    }

    /**
     * Check one or two rate limit windows for an action.
     * Pass action name (e.g. 'send'); if 'send_burst' also exists in config, both are checked.
     * Exits with 429 if any limit is exceeded.
     */
    public static function check(PDO $db, string $action, array $limits): void {
        $ip  = self::clientIp();
        $now = time();

        // Primary window
        if (isset($limits[$action])) {
            self::checkBucket($db, $ip, $action, $limits[$action][0], $limits[$action][1], $now);
        }

        // Burst window (optional — named "{action}_burst")
        $burst = $action . '_burst';
        if (isset($limits[$burst])) {
            self::checkBucket($db, $ip, $burst, $limits[$burst][0], $limits[$burst][1], $now);
        }
    }

    private static function checkBucket(
        PDO $db, string $ip, string $bucket,
        int $max, int $window, int $now
    ): void {
        $stmt = $db->prepare("SELECT hits, window_start FROM rate_limit WHERE ip=? AND bucket=?");
        $stmt->execute([$ip, $bucket]);
        $row = $stmt->fetch();

        if (!$row || ($now - $row['window_start']) >= $window) {
            $db->prepare("INSERT OR REPLACE INTO rate_limit (ip, bucket, hits, window_start) VALUES (?,?,1,?)")
               ->execute([$ip, $bucket, $now]);
            return;
        }

        if ($row['hits'] >= $max) {
            $retryAfter = $window - ($now - $row['window_start']);
            $resetAt    = $row['window_start'] + $window;
            header('Retry-After: ' . $retryAfter);
            header('X-RateLimit-Limit: ' . $max);
            header('X-RateLimit-Remaining: 0');
            header('X-RateLimit-Reset: ' . $resetAt);
            http_response_code(429);
            echo json_encode([
                'error'       => 'rate_limited',
                'bucket'      => $bucket,
                'limit'       => $max,
                'window_sec'  => $window,
                'retry_after' => $retryAfter,
                'reset_at'    => $resetAt,
            ]);
            exit;
        }

        $remaining = $max - $row['hits'] - 1;
        header('X-RateLimit-Limit: ' . $max);
        header('X-RateLimit-Remaining: ' . $remaining);
        header('X-RateLimit-Reset: ' . ($row['window_start'] + $window));

        $db->prepare("UPDATE rate_limit SET hits = hits + 1 WHERE ip=? AND bucket=?")
           ->execute([$ip, $bucket]);
    }

    private static function clientIp(): string {
        // Trust Cloudflare header first, then X-Forwarded-For, then direct IP
        foreach (['HTTP_CF_CONNECTING_IP', 'HTTP_X_FORWARDED_FOR', 'REMOTE_ADDR'] as $key) {
            $val = $_SERVER[$key] ?? '';
            if ($val) return trim(explode(',', $val)[0]);
        }
        return 'unknown';
    }

    public static function cleanup(PDO $db): void {
        // Remove buckets whose window expired more than 2h ago
        $db->prepare("DELETE FROM rate_limit WHERE window_start < ?")->execute([time() - 7200]);
    }
}
