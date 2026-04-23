<?php
declare(strict_types=1);
session_start();

// ─── Config ───────────────────────────────────────────────────────────────────
$BASE = (isset($_SERVER['HTTPS']) && $_SERVER['HTTPS'] === 'on' ? 'https' : 'http')
      . '://' . $_SERVER['HTTP_HOST']
      . rtrim(dirname($_SERVER['SCRIPT_NAME']), '/');
$API  = $BASE . '/api/v1';

// ─── Helpers ──────────────────────────────────────────────────────────────────
function apiCall(string $method, string $url, ?array $body = null, ?string $token = null): array {
    $ch = curl_init($url);
    $headers = ['Content-Type: application/json', 'Accept: application/json'];
    if ($token) $headers[] = 'Authorization: Bearer ' . $token;
    curl_setopt_array($ch, [
        CURLOPT_CUSTOMREQUEST  => $method,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER     => $headers,
        CURLOPT_TIMEOUT        => 10,
        CURLOPT_SSL_VERIFYPEER => false,
    ]);
    if ($body !== null) curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($body));
    $raw  = curl_exec($ch);
    $code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $err  = curl_error($ch);
    curl_close($ch);
    return ['code' => $code, 'body' => $raw ? json_decode($raw, true) : null, 'raw' => $raw, 'err' => $err];
}

function genUUID(): string {
    $d = random_bytes(16);
    $d[6] = chr((ord($d[6]) & 0x0f) | 0x40);
    $d[8] = chr((ord($d[8]) & 0x3f) | 0x80);
    return vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($d), 4));
}
function fakePublicKey(): string { return base64_encode(random_bytes(32)); }

// ─── Actions ──────────────────────────────────────────────────────────────────
$result = null;
$action = $_POST['action'] ?? '';

if ($action === 'clear_session') { session_destroy(); header('Location: ' . $_SERVER['SCRIPT_NAME']); exit; }

// ── Health ──
if ($action === 'health') {
    $result = ['label' => 'GET /health', 'res' => apiCall('GET', $API . '/health')];
}

// ── Register (first time) ──
if ($action === 'register') {
    $userId    = trim($_POST['user_id']    ?? '') ?: genUUID();
    $deviceId  = trim($_POST['device_id']  ?? '') ?: genUUID();
    $publicKey = trim($_POST['public_key'] ?? '') ?: fakePublicKey();
    $slot      = $_POST['slot'] ?? 'A';

    $res = apiCall('POST', $API . '/devices/register', [
        'user_id' => $userId, 'device_id' => $deviceId, 'public_key' => $publicKey,
    ]);
    if ($res['code'] === 201 && isset($res['body']['token'])) {
        $_SESSION['devices'][$slot] = ['user_id'=>$userId,'device_id'=>$deviceId,'public_key'=>$publicKey,'token'=>$res['body']['token']];
    }
    $result = ['label' => 'POST /devices/register', 'res' => $res];
}

// ── Register conflict test ──
if ($action === 'register_conflict') {
    $slot   = $_POST['conflict_slot'] ?? 'A';
    $device = $_SESSION['devices'][$slot] ?? null;
    if (!$device) { $result = ['label' => 'تست تکرار ثبت', 'err' => 'دستگاه ' . $slot . ' ثبت نشده']; }
    else {
        $res = apiCall('POST', $API . '/devices/register', [
            'user_id' => $device['user_id'], 'device_id' => $device['device_id'],
            'public_key' => fakePublicKey(),
        ]);
        $result = ['label' => 'POST /devices/register (تکرار — باید 409 بگیرد)', 'res' => $res];
    }
}

// ── Update / Key Rotation ──
if ($action === 'update') {
    $slot      = $_POST['update_slot'] ?? 'A';
    $device    = $_SESSION['devices'][$slot] ?? null;
    $newPubKey = trim($_POST['new_public_key'] ?? '') ?: fakePublicKey();
    if (!$device) { $result = ['label' => 'PUT /devices/update', 'err' => 'دستگاه ' . $slot . ' ثبت نشده']; }
    else {
        $res = apiCall('PUT', $API . '/devices/update', ['public_key' => $newPubKey], $device['token']);
        if ($res['code'] === 200 && isset($res['body']['token'])) {
            $_SESSION['devices'][$slot]['public_key'] = $newPubKey;
            $_SESSION['devices'][$slot]['token']      = $res['body']['token'];
        }
        $result = ['label' => 'PUT /devices/update (چرخش کلید)', 'res' => $res];
    }
}

// ── Pubkey Lookup ──
if ($action === 'pubkey') {
    $slot     = $_POST['lookup_slot'] ?? 'A';
    $device   = $_SESSION['devices'][$slot] ?? null;
    $targetId = trim($_POST['target_user_id'] ?? '');
    if (!$device) { $result = ['label' => 'GET /devices/{userId}/pubkey', 'err' => 'دستگاه ' . $slot . ' برای auth ثبت نشده']; }
    elseif (!$targetId) { $result = ['label' => 'GET /devices/{userId}/pubkey', 'err' => 'user_id هدف وارد نشده']; }
    else {
        $res = apiCall('GET', $API . '/devices/' . urlencode($targetId) . '/pubkey', null, $device['token']);
        $result = ['label' => 'GET /devices/' . $targetId . '/pubkey', 'res' => $res];
    }
}

// ── Send Message ──
if ($action === 'send') {
    $fromSlot  = $_POST['from_slot'] ?? 'A';
    $toSlot    = $_POST['to_slot']   ?? 'B';
    $sender    = $_SESSION['devices'][$fromSlot] ?? null;
    $recipient = $_SESSION['devices'][$toSlot]   ?? null;
    if (!$sender)    { $result = ['label' => 'POST /messages', 'err' => 'دستگاه فرستنده (' . $fromSlot . ') ثبت نشده']; }
    elseif (!$recipient) { $result = ['label' => 'POST /messages', 'err' => 'دستگاه گیرنده (' . $toSlot . ') ثبت نشده']; }
    else {
        $plaintext  = $_POST['plaintext'] ?? 'سلام از راز!';
        $ciphertext = base64_encode('FAKE_CIPHER::' . $plaintext . '::' . bin2hex(random_bytes(8)));
        $msgId      = trim($_POST['msg_id'] ?? '') ?: null;
        $body = ['recipient_device_id' => $recipient['device_id'], 'ciphertext' => $ciphertext];
        if ($msgId) $body['message_id'] = $msgId;
        $res = apiCall('POST', $API . '/messages', $body, $sender['token']);
        if ($res['code'] === 201 && isset($res['body']['server_message_id'])) {
            $_SESSION['last_sent_id'] = $res['body']['server_message_id'];
        }
        $result = ['label' => 'POST /messages', 'res' => $res];
    }
}

// ── Idempotent send test ──
if ($action === 'send_idempotent') {
    $fromSlot  = $_POST['idem_from'] ?? 'A';
    $toSlot    = $_POST['idem_to']   ?? 'B';
    $sender    = $_SESSION['devices'][$fromSlot] ?? null;
    $recipient = $_SESSION['devices'][$toSlot]   ?? null;
    if (!$sender || !$recipient) {
        $result = ['label' => 'تست Idempotent', 'err' => 'دستگاه‌های A و B باید ثبت شده باشند'];
    } else {
        $fixedId    = 'idem-test-' . substr(md5($fromSlot . $toSlot), 0, 8);
        $ciphertext = base64_encode('IDEMPOTENT_TEST::' . time());
        $body = ['recipient_device_id' => $recipient['device_id'], 'ciphertext' => $ciphertext, 'message_id' => $fixedId];
        $r1 = apiCall('POST', $API . '/messages', $body, $sender['token']);
        $r2 = apiCall('POST', $API . '/messages', $body, $sender['token']);
        $result = ['label' => 'تست Idempotent — دو بار ارسال یک message_id ثابت', 'steps' => [
            ['label' => 'ارسال اول', 'res' => $r1],
            ['label' => 'ارسال دوم (باید همان server_message_id برگردد)', 'res' => $r2],
        ]];
    }
}

// ── Pull ──
if ($action === 'pull') {
    $slot   = $_POST['pull_slot'] ?? 'B';
    $device = $_SESSION['devices'][$slot] ?? null;
    if (!$device) { $result = ['label' => 'GET /messages', 'err' => 'دستگاه ' . $slot . ' ثبت نشده']; }
    else {
        $res = apiCall('GET', $API . '/messages', null, $device['token']);
        $_SESSION['last_pull_slot'] = $slot;
        $_SESSION['last_pull_msgs'] = $res['body']['messages'] ?? [];
        $result = ['label' => 'GET /messages', 'res' => $res];
    }
}

// ── ACK ──
if ($action === 'ack') {
    $slot   = $_POST['ack_slot']   ?? 'B';
    $msgId  = trim($_POST['ack_msg_id'] ?? '');
    $device = $_SESSION['devices'][$slot] ?? null;
    if (!$device) { $result = ['label' => 'DELETE /messages/{id}', 'err' => 'دستگاه ' . $slot . ' ثبت نشده']; }
    elseif (!$msgId) { $result = ['label' => 'DELETE /messages/{id}', 'err' => 'message_id وارد نشده']; }
    else {
        $res = apiCall('DELETE', $API . '/messages/' . urlencode($msgId), null, $device['token']);
        $result = ['label' => 'DELETE /messages/' . $msgId, 'res' => $res];
    }
}

// ── Receipts ──
if ($action === 'receipts') {
    $slot   = $_POST['receipts_slot'] ?? 'A';
    $device = $_SESSION['devices'][$slot] ?? null;
    if (!$device) { $result = ['label' => 'GET /receipts', 'err' => 'دستگاه ' . $slot . ' ثبت نشده']; }
    else {
        $res = apiCall('GET', $API . '/receipts', null, $device['token']);
        $result = ['label' => 'GET /receipts', 'res' => $res];
    }
}

// ── Auth failure test ──
if ($action === 'auth_fail') {
    $res = apiCall('GET', $API . '/messages', null, 'invalid_token_abc123');
    $result = ['label' => 'GET /messages با token نامعتبر (باید 401 بگیرد)', 'res' => $res];
}

// ── Rate limit test ──
if ($action === 'rate_test') {
    $steps = [];
    for ($i = 1; $i <= 12; $i++) {
        $r = apiCall('POST', $API . '/devices/register', [
            'user_id' => genUUID(), 'device_id' => genUUID(), 'public_key' => fakePublicKey(),
        ]);
        $steps[] = ['label' => 'درخواست ' . $i, 'res' => $r];
        if ($r['code'] === 429) break;
    }
    $result = ['label' => 'تست Rate Limit — ارسال مکرر POST /devices/register', 'steps' => $steps];
}

// ── Full E2E Flow ──
if ($action === 'full_flow') {
    $steps = [];
    $uA = genUUID(); $dA = genUUID(); $kA = fakePublicKey();
    $uB = genUUID(); $dB = genUUID(); $kB = fakePublicKey();

    $r1 = apiCall('POST', $API . '/devices/register', ['user_id'=>$uA,'device_id'=>$dA,'public_key'=>$kA]);
    $steps[] = ['label' => '1. ثبت دستگاه A (POST /devices/register)', 'res' => $r1];
    $tokenA  = $r1['body']['token'] ?? null;

    $r2 = apiCall('POST', $API . '/devices/register', ['user_id'=>$uB,'device_id'=>$dB,'public_key'=>$kB]);
    $steps[] = ['label' => '2. ثبت دستگاه B (POST /devices/register)', 'res' => $r2];
    $tokenB  = $r2['body']['token'] ?? null;

    if ($tokenA && $tokenB) {
        // A looks up B's public key before sending (real flow)
        $r3 = apiCall('GET', $API . '/devices/' . $uB . '/pubkey', null, $tokenA);
        $steps[] = ['label' => '3. A کلید عمومی B را می‌گیرد (GET /devices/{userId}/pubkey)', 'res' => $r3];

        // A encrypts and sends
        $ct  = base64_encode('E2E_TEST_CIPHER::msg=' . time() . '::nonce=' . bin2hex(random_bytes(12)));
        $r4  = apiCall('POST', $API . '/messages', ['recipient_device_id'=>$dB,'ciphertext'=>$ct], $tokenA);
        $steps[] = ['label' => '4. A پیام رمزنگاری‌شده به B ارسال می‌کند (POST /messages)', 'res' => $r4];
        $srvId = $r4['body']['server_message_id'] ?? null;

        // B pulls
        $r5 = apiCall('GET', $API . '/messages', null, $tokenB);
        $steps[] = ['label' => '5. B پیام‌های در انتظار را می‌گیرد (GET /messages)', 'res' => $r5];

        if ($srvId) {
            // B acks
            $r6 = apiCall('DELETE', $API . '/messages/' . $srvId, null, $tokenB);
            $steps[] = ['label' => '6. B تحویل را تایید می‌کند — پیام از سرور حذف می‌شود (DELETE /messages/{id})', 'res' => $r6];

            // A checks receipts
            $r7 = apiCall('GET', $API . '/receipts', null, $tokenA);
            $steps[] = ['label' => '7. A رسید تحویل را بررسی می‌کند (GET /receipts)', 'res' => $r7];

            // Confirm B inbox is empty
            $r8 = apiCall('GET', $API . '/messages', null, $tokenB);
            $steps[] = ['label' => '8. صندوق B بعد از ACK خالی است (GET /messages — باید [] باشد)', 'res' => $r8];

            // A rotates key
            $newKeyA = fakePublicKey();
            $r9 = apiCall('PUT', $API . '/devices/update', ['public_key' => $newKeyA], $tokenA);
            $steps[] = ['label' => '9. A کلید عمومی خود را به‌روز می‌کند و token جدید می‌گیرد (PUT /devices/update)', 'res' => $r9];
            $newTokenA = $r9['body']['token'] ?? $tokenA;

            // B looks up A's new key
            $r10 = apiCall('GET', $API . '/devices/' . $uA . '/pubkey', null, $tokenB);
            $steps[] = ['label' => '10. B کلید جدید A را می‌گیرد (GET /devices/{userId}/pubkey)', 'res' => $r10];

            // Try re-register conflict
            $r11 = apiCall('POST', $API . '/devices/register', ['user_id'=>$uA,'device_id'=>$dA,'public_key'=>fakePublicKey()]);
            $steps[] = ['label' => '11. تلاش برای ثبت دوباره دستگاه A بدون token (باید 409 بگیرد)', 'res' => $r11];

            $_SESSION['devices']['A_e2e'] = ['user_id'=>$uA,'device_id'=>$dA,'public_key'=>$newKeyA,'token'=>$newTokenA];
            $_SESSION['devices']['B_e2e'] = ['user_id'=>$uB,'device_id'=>$dB,'public_key'=>$kB,'token'=>$tokenB];
        }
    }
    $result = ['label' => 'تست کامل جریان E2E — ۱۱ مرحله', 'steps' => $steps];
}

// ── File Upload (single chunk — small file test) ──
if ($action === 'file_upload') {
    $fromSlot  = $_POST['file_from'] ?? 'A';
    $toSlot    = $_POST['file_to']   ?? 'B';
    $sender    = $_SESSION['devices'][$fromSlot] ?? null;
    $recipient = $_SESSION['devices'][$toSlot]   ?? null;
    if (!$sender || !$recipient) {
        $result = ['label' => 'POST /files/upload', 'err' => 'دستگاه‌های فرستنده و گیرنده باید ثبت شده باشند'];
    } else {
        // Simulate a small encrypted voice/file payload
        $fakeContent  = 'FAKE_ENCRYPTED_FILE::' . bin2hex(random_bytes(64));
        $chunkData    = base64_encode($fakeContent);
        $sizeBytes    = strlen($fakeContent);
        $fileId       = trim($_POST['file_id_upload'] ?? '') ?: null;
        $body = [
            'recipient_device_id' => $recipient['device_id'],
            'file_name_enc'       => base64_encode('voice_' . date('His') . '.ogg'),
            'mime_type_enc'       => base64_encode('audio/ogg'),
            'size_bytes'          => $sizeBytes,
            'chunk_index'         => 0,
            'chunk_count'         => 1,
            'chunk_data'          => $chunkData,
        ];
        if ($fileId) $body['file_id'] = $fileId;
        $res = apiCall('POST', $API . '/files/upload', $body, $sender['token']);
        if (isset($res['body']['file_id'])) $_SESSION['last_file_id'] = $res['body']['file_id'];
        $result = ['label' => 'POST /files/upload (یک chunk)', 'res' => $res];
    }
}

// ── File Upload Multi-chunk test ──
if ($action === 'file_upload_chunked') {
    $fromSlot  = $_POST['chunk_from'] ?? 'A';
    $toSlot    = $_POST['chunk_to']   ?? 'B';
    $sender    = $_SESSION['devices'][$fromSlot] ?? null;
    $recipient = $_SESSION['devices'][$toSlot]   ?? null;
    if (!$sender || !$recipient) {
        $result = ['label' => 'آپلود chunked', 'err' => 'دستگاه‌ها ثبت نشده‌اند'];
    } else {
        $chunkCount = 3;
        $fileId     = bin2hex(random_bytes(8));
        $steps      = [];
        for ($i = 0; $i < $chunkCount; $i++) {
            $fakeChunk = 'CHUNK_' . $i . '::' . bin2hex(random_bytes(32));
            $body = [
                'recipient_device_id' => $recipient['device_id'],
                'file_id'             => $fileId,
                'file_name_enc'       => base64_encode('big_audio.ogg'),
                'mime_type_enc'       => base64_encode('audio/ogg'),
                'size_bytes'          => $chunkCount * 100,
                'chunk_index'         => $i,
                'chunk_count'         => $chunkCount,
                'chunk_data'          => base64_encode($fakeChunk),
            ];
            $r = apiCall('POST', $API . '/files/upload', $body, $sender['token']);
            $pct = $r['body']['progress_pct'] ?? '?';
            $steps[] = ['label' => 'chunk ' . $i . ' از ' . ($chunkCount-1) . ' — پیشرفت: ' . $pct . '%', 'res' => $r];
        }
        $_SESSION['last_file_id'] = $fileId;
        $result = ['label' => 'آپلود فایل chunked (' . $chunkCount . ' قطعه) — POST /files/upload', 'steps' => $steps];
    }
}

// ── List pending files ──
if ($action === 'file_list') {
    $slot   = $_POST['file_list_slot'] ?? 'B';
    $device = $_SESSION['devices'][$slot] ?? null;
    if (!$device) { $result = ['label' => 'GET /files', 'err' => 'دستگاه ' . $slot . ' ثبت نشده']; }
    else {
        $res = apiCall('GET', $API . '/files', null, $device['token']);
        $_SESSION['last_file_list'] = $res['body']['files'] ?? [];
        $result = ['label' => 'GET /files (لیست فایل‌های در انتظار)', 'res' => $res];
    }
}

// ── Download chunk ──
if ($action === 'file_download') {
    $slot    = $_POST['dl_slot']    ?? 'B';
    $fileId  = trim($_POST['dl_file_id']  ?? $_SESSION['last_file_id'] ?? '');
    $chunkIdx= (int)($_POST['dl_chunk']   ?? 0);
    $device  = $_SESSION['devices'][$slot] ?? null;
    if (!$device) { $result = ['label' => 'GET /files/{id}/chunk/{index}', 'err' => 'دستگاه ' . $slot . ' ثبت نشده']; }
    elseif (!$fileId) { $result = ['label' => 'GET /files/{id}/chunk/{index}', 'err' => 'file_id وارد نشده']; }
    else {
        // For display we just show the response headers via a HEAD-style check
        $res = apiCall('GET', $API . '/files/' . urlencode($fileId) . '/chunk/' . $chunkIdx, null, $device['token']);
        // If binary response, show size instead of raw
        if ($res['code'] === 200 && isset($res['raw']) && !isset($res['body'])) {
            $result = ['label' => 'GET /files/' . $fileId . '/chunk/' . $chunkIdx,
                'res' => ['code' => 200, 'body' => ['binary_size_bytes' => strlen($res['raw'] ?? '')], 'err' => '']];
        } else {
            $result = ['label' => 'GET /files/' . $fileId . '/chunk/' . $chunkIdx, 'res' => $res];
        }
    }
}

// ── ACK file ──
if ($action === 'file_ack') {
    $slot   = $_POST['fack_slot']   ?? 'B';
    $fileId = trim($_POST['fack_id'] ?? $_SESSION['last_file_id'] ?? '');
    $device = $_SESSION['devices'][$slot] ?? null;
    if (!$device) { $result = ['label' => 'DELETE /files/{id}', 'err' => 'دستگاه ' . $slot . ' ثبت نشده']; }
    elseif (!$fileId) { $result = ['label' => 'DELETE /files/{id}', 'err' => 'file_id وارد نشده']; }
    else {
        $res = apiCall('DELETE', $API . '/files/' . urlencode($fileId), null, $device['token']);
        $result = ['label' => 'DELETE /files/' . $fileId . ' (تایید دریافت فایل)', 'res' => $res];
    }
}

// ── Full E2E with file ──
if ($action === 'full_flow_file') {
    $steps = [];
    $uA = genUUID(); $dA = genUUID(); $kA = fakePublicKey();
    $uB = genUUID(); $dB = genUUID(); $kB = fakePublicKey();

    $r1 = apiCall('POST', $API . '/devices/register', ['user_id'=>$uA,'device_id'=>$dA,'public_key'=>$kA]);
    $steps[] = ['label' => '1. ثبت A', 'res' => $r1]; $tokenA = $r1['body']['token'] ?? null;
    $r2 = apiCall('POST', $API . '/devices/register', ['user_id'=>$uB,'device_id'=>$dB,'public_key'=>$kB]);
    $steps[] = ['label' => '2. ثبت B', 'res' => $r2]; $tokenB = $r2['body']['token'] ?? null;

    if ($tokenA && $tokenB) {
        // Upload 2-chunk file
        $fileId = bin2hex(random_bytes(8));
        for ($i = 0; $i < 2; $i++) {
            $body = ['recipient_device_id'=>$dB,'file_id'=>$fileId,
                'file_name_enc'=>base64_encode('voice.ogg'),'mime_type_enc'=>base64_encode('audio/ogg'),
                'size_bytes'=>200,'chunk_index'=>$i,'chunk_count'=>2,
                'chunk_data'=>base64_encode('CHUNK_'.$i.'::'.bin2hex(random_bytes(20)))];
            $r = apiCall('POST', $API . '/files/upload', $body, $tokenA);
            $pct = $r['body']['progress_pct'] ?? '?';
            $steps[] = ['label' => '3.' . ($i+1) . ' آپلود chunk ' . $i . ' — پیشرفت: ' . $pct . '% (POST /files/upload)', 'res' => $r];
        }

        // B lists files
        $r4 = apiCall('GET', $API . '/files', null, $tokenB);
        $steps[] = ['label' => '4. B لیست فایل‌های دریافتی را می‌گیرد (GET /files)', 'res' => $r4];

        // B downloads each chunk
        for ($i = 0; $i < 2; $i++) {
            $r = apiCall('GET', $API . '/files/' . $fileId . '/chunk/' . $i, null, $tokenB);
            $size = isset($r['raw']) && !$r['body'] ? strlen($r['raw']) : null;
            $steps[] = ['label' => '5.' . ($i+1) . ' B دانلود chunk ' . $i . ' — ' . ($size ? $size . ' bytes' : '') . ' (GET /files/{id}/chunk/{index})', 'res' => $size ? ['code'=>200,'body'=>['bytes'=>$size],'err'=>''] : $r];
        }

        // B ACKs file
        $r6 = apiCall('DELETE', $API . '/files/' . $fileId, null, $tokenB);
        $steps[] = ['label' => '6. B تحویل فایل را تایید می‌کند — فایل از سرور حذف می‌شود (DELETE /files/{id})', 'res' => $r6];

        // A checks receipt
        $r7 = apiCall('GET', $API . '/receipts', null, $tokenA);
        $steps[] = ['label' => '7. A رسید دریافت فایل را می‌بیند (GET /receipts)', 'res' => $r7];

        // Confirm B file list empty
        $r8 = apiCall('GET', $API . '/files', null, $tokenB);
        $steps[] = ['label' => '8. صندوق فایل B خالی است (GET /files — باید [] باشد)', 'res' => $r8];

        $_SESSION['devices']['FA_e2e'] = ['user_id'=>$uA,'device_id'=>$dA,'public_key'=>$kA,'token'=>$tokenA];
        $_SESSION['devices']['FB_e2e'] = ['user_id'=>$uB,'device_id'=>$dB,'public_key'=>$kB,'token'=>$tokenB];
    }
    $result = ['label' => 'تست کامل جریان فایل E2E — ۸ مرحله', 'steps' => $steps];
}

// ─── Render helpers ───────────────────────────────────────────────────────────
$devices      = $_SESSION['devices'] ?? [];
$lastPullMsgs = $_SESSION['last_pull_msgs'] ?? [];
$lastPullSlot = $_SESSION['last_pull_slot'] ?? 'B';
$lastSentId   = $_SESSION['last_sent_id'] ?? '';

function statusBadge(int $code): string {
    if ($code >= 200 && $code < 300) $color = '#22c55e';
    elseif ($code === 429)           $color = '#f59e0b';
    elseif ($code >= 400)            $color = '#ef4444';
    else                             $color = '#64748b';
    return '<span class="http-code" style="background:'.$color.';">'.$code.'</span>';
}

function prettyJson(mixed $data): string {
    $json = json_encode($data, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT);
    // Colorize
    $json = preg_replace('/"([^"]+)":/', '<span class="jk">"$1"</span>:', $json);
    $json = preg_replace('/: "([^"]*)"/', ': <span class="jv">"$1"</span>', $json);
    $json = preg_replace('/: (\d+)/', ': <span class="jn">$1</span>', $json);
    $json = preg_replace('/: (true|false|null)/', ': <span class="jb">$1</span>', $json);
    return '<pre class="json-block">' . $json . '</pre>';
}

function renderResult(array $r): string {
    $html = '<div class="result-box">';
    $html .= '<div class="result-label">' . htmlspecialchars($r['label']) . '</div>';
    if (isset($r['err'])) {
        $html .= '<div class="err-msg">خطا: ' . htmlspecialchars($r['err']) . '</div>';
    } elseif (isset($r['steps'])) {
        foreach ($r['steps'] as $i => $step) {
            $ok = ($step['res']['code'] >= 200 && $step['res']['code'] < 300);
            $html .= '<div class="step-item ' . ($ok ? 'step-ok' : 'step-fail') . '">';
            $html .= '<div class="step-head">' . statusBadge($step['res']['code']) . ' <strong>' . htmlspecialchars($step['label']) . '</strong></div>';
            $html .= prettyJson($step['res']['body'] ?? $step['res']['raw']);
            $html .= '</div>';
        }
    } elseif (isset($r['res'])) {
        $html .= '<div style="margin-bottom:8px">' . statusBadge($r['res']['code']) . '</div>';
        if ($r['res']['err']) $html .= '<div class="err-msg">cURL: ' . htmlspecialchars($r['res']['err']) . '</div>';
        $html .= prettyJson($r['res']['body'] ?? $r['res']['raw']);
    }
    $html .= '</div>';
    return $html;
}

function slotSelect(string $name, string $default = 'A', array $opts = ['A','B','C']): string {
    $html = '<select name="' . $name . '">';
    foreach ($opts as $o) $html .= '<option value="'.$o.'"'.($o===$default?' selected':'').'>دستگاه '.$o.'</option>';
    $html .= '</select>';
    return $html;
}
?>
<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Raaz API Tester</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:Tahoma,Arial,sans-serif;background:#080f1a;color:#cbd5e1;min-height:100vh}
a{color:#818cf8}
h1{text-align:center;padding:28px 16px 6px;font-size:22px;color:#a5b4fc;letter-spacing:1px}
.subtitle{text-align:center;color:#475569;font-size:12px;margin-bottom:20px}
.container{max-width:1200px;margin:0 auto;padding:0 14px 60px}

/* Session bar */
.session-bar{background:#0f1f35;border:1px solid #1e3a5f;border-radius:10px;padding:10px 16px;
  margin-bottom:18px;display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.session-bar span{font-size:12px;color:#64748b}
.tag{background:#0a1628;border:1px solid #1e3a5f;border-radius:4px;padding:2px 8px;font-size:11px;color:#93c5fd;font-family:monospace}

/* Grid */
.grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}
@media(max-width:720px){.grid{grid-template-columns:1fr}}
.full{grid-column:1/-1}

/* Card */
.card{background:#0f1f35;border:1px solid #1e3a5f;border-radius:12px;padding:18px;display:flex;flex-direction:column;gap:0}
.card-header{margin-bottom:10px}
.card-title{font-size:14px;font-weight:700;color:#94a3b8;display:flex;align-items:center;gap:8px;margin-bottom:4px}
.card-desc{font-size:11px;color:#334155;line-height:1.7;background:#060d18;border-radius:6px;padding:8px 10px;border-right:3px solid #1e3a5f}
.card-desc b{color:#64748b}
.card-desc code{color:#7dd3fc;font-size:11px}

/* Method badges */
.badge{display:inline-block;font-size:10px;padding:2px 7px;border-radius:4px;font-weight:700;font-family:monospace}
.GET{background:#14532d;color:#86efac}
.POST{background:#1e3a5f;color:#93c5fd}
.PUT{background:#44403c;color:#fde68a}
.DELETE{background:#450a0a;color:#fca5a5}

/* Form elements */
label{font-size:11px;color:#64748b;display:block;margin-bottom:3px;margin-top:10px}
input,select,textarea{width:100%;background:#060d18;border:1px solid #1e3a5f;color:#cbd5e1;
  border-radius:6px;padding:7px 10px;font-size:12px;font-family:Tahoma,Arial,sans-serif;direction:rtl}
input::placeholder{color:#334155}
input:focus,select:focus{outline:none;border-color:#4f46e5}
textarea{resize:vertical;min-height:60px}

/* Buttons */
.btn{border:none;padding:8px 18px;border-radius:8px;cursor:pointer;font-size:12px;font-weight:700;
  margin-top:12px;width:100%;font-family:Tahoma,Arial,sans-serif;transition:opacity .15s}
.btn:hover{opacity:.85}
.btn-blue{background:#1d4ed8;color:#fff}
.btn-green{background:#15803d;color:#fff}
.btn-red{background:#b91c1c;color:#fff}
.btn-yellow{background:#b45309;color:#fff}
.btn-purple{background:#6d28d9;color:#fff}
.btn-gray{background:#334155;color:#fff}
.btn-sm{padding:4px 10px;width:auto;margin-top:0;font-size:11px}

/* Results */
.result-box{background:#060d18;border:1px solid #1e3a5f;border-radius:8px;padding:12px;margin-top:12px}
.result-label{font-size:11px;color:#4f46e5;font-weight:700;margin-bottom:8px;font-family:monospace}
.err-msg{color:#ef4444;font-size:12px;padding:6px;background:#1a0505;border-radius:4px;margin:4px 0}
.http-code{color:#fff;padding:2px 8px;border-radius:4px;font-size:11px;font-weight:700;font-family:monospace}
.step-item{margin-bottom:10px;padding:10px;border-radius:6px;border:1px solid #1e293b}
.step-ok{border-color:#14532d;background:#0a1f12}
.step-fail{border-color:#7f1d1d;background:#1a0505}
.step-head{margin-bottom:6px;font-size:12px}

/* JSON */
.json-block{margin:6px 0 0;font-size:11px;overflow-x:auto;white-space:pre-wrap;word-break:break-all;
  color:#94a3b8;line-height:1.6;font-family:'Courier New',monospace}
.jk{color:#93c5fd}.jv{color:#86efac}.jn{color:#fde68a}.jb{color:#f9a8d4}

/* Device cards */
.device-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:8px;margin-top:8px}
.device-card{background:#060d18;border:1px solid #1e3a5f;border-radius:8px;padding:10px;font-size:11px}
.device-card .slot{font-weight:700;color:#818cf8;font-size:13px;margin-bottom:4px}
.device-card .df{color:#334155;margin:2px 0}
.device-card code{color:#7dd3fc;word-break:break-all;font-size:10px}

/* Pull messages list */
.msg-row{background:#060d18;border:1px solid #1e3a5f;border-radius:6px;padding:8px 10px;
  margin-bottom:6px;font-size:11px;display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap}
.msg-id{color:#a5f3fc;font-family:monospace;font-size:10px;word-break:break-all}

/* Section separator */
.section-title{font-size:11px;text-transform:uppercase;letter-spacing:2px;color:#334155;
  text-align:center;padding:14px 0 8px;grid-column:1/-1}
hr.div{border:none;border-top:1px solid #1e293b;margin:6px 0;grid-column:1/-1}

/* Toast */
#toast{position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:#4f46e5;
  color:#fff;padding:8px 22px;border-radius:8px;font-size:12px;display:none;z-index:9999}
</style>
</head>
<body>
<h1>Raaz API Tester</h1>
<p class="subtitle">تستر کامل سرور راز — همه endpoint‌ها با توضیح کامل هر مرحله</p>

<div class="container">

<!-- Session bar -->
<div class="session-bar">
  <span>API: <span class="tag"><?= htmlspecialchars($API) ?></span></span>
  <span>دستگاه‌های ثبت‌شده: <span class="tag"><?= count($devices) ?></span></span>
  <?php if ($lastSentId): ?>
    <span>آخرین msg_id: <span class="tag"><?= htmlspecialchars(substr($lastSentId,0,20)) ?>…</span></span>
  <?php endif; ?>
  <form method="post" style="margin-right:auto">
    <input type="hidden" name="action" value="clear_session">
    <button class="btn btn-red btn-sm" type="submit">پاک کردن نشست</button>
  </form>
</div>

<?php if (!extension_loaded('curl')): ?>
<div style="background:#422006;border:1px solid #92400e;border-radius:8px;padding:10px 14px;color:#fbbf24;font-size:12px;margin-bottom:14px">
  ⚠️ افزونه cURL در PHP فعال نیست — تستر کار نمی‌کند!
</div>
<?php endif; ?>

<!-- Devices in session -->
<?php if (!empty($devices)): ?>
<div class="card full" style="margin-bottom:14px">
  <div class="card-title">دستگاه‌های فعال در نشست</div>
  <div class="device-grid">
  <?php foreach ($devices as $slot => $d): ?>
    <div class="device-card">
      <div class="slot">دستگاه <?= htmlspecialchars($slot) ?></div>
      <div class="df">user_id: <code><?= htmlspecialchars($d['user_id']) ?></code></div>
      <div class="df">device_id: <code><?= htmlspecialchars($d['device_id']) ?></code></div>
      <div class="df">pubkey: <code><?= htmlspecialchars(substr($d['public_key'],0,20)) ?>…</code></div>
      <div class="df">token: <code><?= htmlspecialchars(substr($d['token'],0,16)) ?>…</code></div>
    </div>
  <?php endforeach; ?>
  </div>
</div>
<?php endif; ?>

<div class="grid">

<!-- ═══════════════════════════════ SECTION 1: سرور ═══════════════════════════ -->
<div class="section-title">① بررسی وضعیت سرور</div>

<!-- Health -->
<div class="card">
  <div class="card-header">
    <div class="card-title"><span class="badge GET">GET</span> /health</div>
    <div class="card-desc">
      <b>هدف:</b> بررسی اینکه سرور زنده و در حال اجراست.<br>
      <b>نیاز به auth:</b> ندارد — اولین endpoint برای تست اتصال.<br>
      <b>پاسخ موفق:</b> <code>200 {"status":"ok","version":"1.0.0","time":...}</code>
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="health">
    <button class="btn btn-green" type="submit">بررسی سلامت سرور</button>
  </form>
  <?php if ($result && $result['label'] === 'GET /health') echo renderResult($result); ?>
</div>

<!-- ═══════════════════════════════ SECTION 2: ثبت دستگاه ════════════════════ -->
<div class="section-title full">② ثبت دستگاه و احراز هویت</div>

<!-- Register -->
<div class="card">
  <div class="card-header">
    <div class="card-title"><span class="badge POST">POST</span> /devices/register</div>
    <div class="card-desc">
      <b>هدف:</b> ثبت یک دستگاه جدید — فقط یک بار مجاز است.<br>
      <b>خروجی:</b> یک <code>token</code> یکبار مصرف (در session ذخیره می‌شود).<br>
      <b>نکته امنیتی:</b> ثبت دوباره همان device_id بدون token → <code>409</code>.<br>
      <b>فیلدهای خودکار:</b> UUID برای user_id و device_id، کلید X25519 تصادفی برای public_key.
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="register">
    <label>ذخیره در نشست با نام</label>
    <?= slotSelect('slot', 'A') ?>
    <label>user_id <small>(اختیاری)</small></label>
    <input type="text" name="user_id" placeholder="خودکار — UUID v4">
    <label>device_id <small>(اختیاری)</small></label>
    <input type="text" name="device_id" placeholder="خودکار — UUID v4">
    <label>public_key <small>(اختیاری — Base64)</small></label>
    <input type="text" name="public_key" placeholder="خودکار — 32 بایت تصادفی">
    <button class="btn btn-blue" type="submit">ثبت دستگاه</button>
  </form>
  <?php if ($result && $result['label'] === 'POST /devices/register') echo renderResult($result); ?>
</div>

<!-- Register conflict test -->
<div class="card">
  <div class="card-header">
    <div class="card-title"><span class="badge POST">POST</span> /devices/register — تست تکرار</div>
    <div class="card-desc">
      <b>هدف:</b> تایید اینکه سرور از ثبت دوباره یک device_id موجود جلوگیری می‌کند.<br>
      <b>انتظار:</b> پاسخ <code>409 Conflict</code> با پیغام <code>device_exists</code>.<br>
      <b>چرا مهم است:</b> بدون این محدودیت، هر کسی می‌توانست کلید عمومی دیگران را عوض کند.
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="register_conflict">
    <label>دستگاه از پیش ثبت‌شده</label>
    <?= slotSelect('conflict_slot', 'A') ?>
    <button class="btn btn-yellow" type="submit">تست ثبت تکراری (باید 409 بگیرد)</button>
  </form>
  <?php if ($result && str_contains($result['label'], 'تکرار')) echo renderResult($result); ?>
</div>

<!-- Update / Key Rotation -->
<div class="card">
  <div class="card-header">
    <div class="card-title"><span class="badge PUT">PUT</span> /devices/update</div>
    <div class="card-desc">
      <b>هدف:</b> چرخش کلید عمومی با احراز هویت — نیاز به token فعلی دارد.<br>
      <b>خروجی:</b> token جدید (token قبلی باطل می‌شود) + کلید جدید ذخیره می‌شود.<br>
      <b>کاربرد واقعی:</b> وقتی کاربر دستگاه عوض می‌کند یا کلید می‌چرخاند.<br>
      <b>نکته:</b> بعد از update، token قبلی دیگر کار نمی‌کند.
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="update">
    <label>دستگاه برای چرخش کلید</label>
    <?= slotSelect('update_slot', 'A') ?>
    <label>کلید عمومی جدید <small>(اختیاری)</small></label>
    <input type="text" name="new_public_key" placeholder="خودکار — 32 بایت تصادفی">
    <button class="btn btn-yellow" type="submit">چرخش کلید و دریافت token جدید</button>
  </form>
  <?php if ($result && str_contains($result['label'], 'چرخش کلید')) echo renderResult($result); ?>
</div>

<!-- Pubkey Lookup -->
<div class="card">
  <div class="card-header">
    <div class="card-title"><span class="badge GET">GET</span> /devices/{userId}/pubkey</div>
    <div class="card-desc">
      <b>هدف:</b> گرفتن کلید عمومی یک کاربر برای رمزنگاری پیام قبل از ارسال.<br>
      <b>نیاز به auth:</b> دارد — فقط دستگاه‌های ثبت‌شده می‌توانند کلید دیگران را ببینند.<br>
      <b>خروجی:</b> لیست دستگاه‌های آن کاربر با public_key هر کدام.<br>
      <b>جریان واقعی:</b> A → GET pubkey of B → رمزنگاری با کلید B → POST /messages
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="pubkey">
    <label>دستگاه برای احراز هویت (requester)</label>
    <?= slotSelect('lookup_slot', 'A') ?>
    <label>user_id هدف (کسی که کلیدش را می‌خواهید)</label>
    <input type="text" name="target_user_id" placeholder="user_id دستگاه B را از بالا کپی کنید">
    <button class="btn btn-blue" type="submit">دریافت کلید عمومی</button>
  </form>
  <?php if ($result && str_contains($result['label'], 'pubkey')) echo renderResult($result); ?>
</div>

<!-- ═══════════════════════════════ SECTION 3: پیام‌رسانی ══════════════════════ -->
<div class="section-title full">③ ارسال و دریافت پیام</div>

<!-- Send -->
<div class="card">
  <div class="card-header">
    <div class="card-title"><span class="badge POST">POST</span> /messages</div>
    <div class="card-desc">
      <b>هدف:</b> ارسال یک blob رمزنگاری‌شده به دستگاه گیرنده.<br>
      <b>سرور چه می‌بیند:</b> فقط یک رشته Base64 — محتوا کاملاً غیرقابل خواندن است.<br>
      <b>حداکثر حجم:</b> 64 کیلوبایت (در config قابل تغییر).<br>
      <b>message_id اختیاری:</b> اگر بدهید → idempotent (دوبار ارسال = یک پیام).
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="send">
    <label>دستگاه فرستنده</label>
    <?= slotSelect('from_slot', 'A') ?>
    <label>دستگاه گیرنده</label>
    <?= slotSelect('to_slot', 'B', ['B','A','C']) ?>
    <label>متن پیام <small>(در تستر شبیه‌سازی می‌شود — سرور فقط Base64 می‌بیند)</small></label>
    <input type="text" name="plaintext" value="سلام از راز!">
    <label>message_id <small>(اختیاری — برای idempotent)</small></label>
    <input type="text" name="msg_id" placeholder="خودکار">
    <button class="btn btn-blue" type="submit">ارسال پیام</button>
  </form>
  <?php if ($result && $result['label'] === 'POST /messages') echo renderResult($result); ?>
</div>

<!-- Idempotent send -->
<div class="card">
  <div class="card-header">
    <div class="card-title"><span class="badge POST">POST</span> /messages — تست Idempotent</div>
    <div class="card-desc">
      <b>هدف:</b> تایید اینکه ارسال دوباره یک message_id ثابت → پیام تکراری در صندوق نمی‌گذارد.<br>
      <b>مکانیزم:</b> سرور از <code>INSERT OR IGNORE</code> استفاده می‌کند.<br>
      <b>کاربرد:</b> وقتی شبکه قطع می‌شود و اپ پیام را دوباره ارسال می‌کند.
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="send_idempotent">
    <label>فرستنده</label><?= slotSelect('idem_from', 'A') ?>
    <label>گیرنده</label><?= slotSelect('idem_to', 'B', ['B','A','C']) ?>
    <button class="btn btn-yellow" type="submit">ارسال دو بار با یک message_id</button>
  </form>
  <?php if ($result && str_contains($result['label'], 'Idempotent')) echo renderResult($result); ?>
</div>

<!-- Pull -->
<div class="card">
  <div class="card-header">
    <div class="card-title"><span class="badge GET">GET</span> /messages</div>
    <div class="card-desc">
      <b>هدف:</b> دریافت تمام پیام‌های در انتظار برای یک دستگاه.<br>
      <b>فقط پیام‌های تایید نشده:</b> پیام‌هایی که ACK نشده‌اند (<code>acked=0</code>).<br>
      <b>حداکثر:</b> 100 پیام در هر بار — به‌ترتیب زمانی.<br>
      <b>بعد از pull:</b> باید برای هر پیام DELETE بزنید تا از سرور پاک شود.
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="pull">
    <label>دستگاه گیرنده</label>
    <?= slotSelect('pull_slot', 'B', ['B','A','C']) ?>
    <button class="btn btn-green" type="submit">دریافت پیام‌های در انتظار</button>
  </form>
  <?php if ($result && $result['label'] === 'GET /messages') echo renderResult($result); ?>

  <?php if (!empty($lastPullMsgs)): ?>
  <hr style="border:none;border-top:1px solid #1e293b;margin:12px 0">
  <div style="font-size:11px;color:#64748b;margin-bottom:6px">
    پیام‌های pull شده برای دستگاه <?= htmlspecialchars($lastPullSlot) ?> — روی ACK کلیک کنید:
  </div>
  <?php foreach ($lastPullMsgs as $msg): ?>
    <div class="msg-row">
      <div>
        <div class="msg-id"><?= htmlspecialchars($msg['server_message_id']) ?></div>
        <div style="color:#334155;margin-top:2px">از: <?= htmlspecialchars($msg['sender_device_id'] ?? '') ?></div>
      </div>
      <form method="post">
        <input type="hidden" name="action" value="ack">
        <input type="hidden" name="ack_slot" value="<?= htmlspecialchars($lastPullSlot) ?>">
        <input type="hidden" name="ack_msg_id" value="<?= htmlspecialchars($msg['server_message_id']) ?>">
        <button class="btn btn-red btn-sm" type="submit">ACK</button>
      </form>
    </div>
  <?php endforeach; ?>
  <?php endif; ?>
</div>

<!-- ACK -->
<div class="card">
  <div class="card-header">
    <div class="card-title"><span class="badge DELETE">DELETE</span> /messages/{id}</div>
    <div class="card-desc">
      <b>هدف:</b> تایید دریافت پیام — سرور پیام را فوراً حذف می‌کند.<br>
      <b>رسید خودکار:</b> بعد از حذف، یک رسید برای فرستنده ذخیره می‌شود.<br>
      <b>امنیت:</b> فقط گیرنده واقعی می‌تواند پیام خودش را حذف کند.<br>
      <b>بعد از pull:</b> ID پیام به صورت خودکار از آخرین ارسال پر شده است.
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="ack">
    <label>دستگاه گیرنده (کسی که پیام برایش بود)</label>
    <?= slotSelect('ack_slot', 'B', ['B','A','C']) ?>
    <label>server_message_id</label>
    <input type="text" name="ack_msg_id" value="<?= htmlspecialchars($lastSentId) ?>" placeholder="از pull کپی کنید">
    <button class="btn btn-red" type="submit">تایید دریافت و حذف از سرور</button>
  </form>
  <?php if ($result && str_starts_with($result['label'], 'DELETE /messages/')) echo renderResult($result); ?>
</div>

<!-- ═══════════════════════════════ SECTION 4: رسیدها ══════════════════════════ -->
<div class="section-title full">④ رسیدهای تحویل</div>

<!-- Receipts -->
<div class="card">
  <div class="card-header">
    <div class="card-title"><span class="badge GET">GET</span> /receipts</div>
    <div class="card-desc">
      <b>هدف:</b> فرستنده می‌فهمد کدام پیام‌هایش توسط گیرنده دریافت و تایید شده.<br>
      <b>delete-after-read:</b> بعد از اینکه رسیدها به فرستنده تحویل داده شد، از سرور حذف می‌شوند.<br>
      <b>جریان:</b> B تایید کرد → رسید ذخیره شد → A رسید را می‌خواند → رسید حذف می‌شود.<br>
      <b>نتیجه:</b> A وضعیت پیام را به <code>confirmed</code> تغییر می‌دهد.
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="receipts">
    <label>دستگاه فرستنده (کسی که رسید می‌خواهد)</label>
    <?= slotSelect('receipts_slot', 'A') ?>
    <button class="btn btn-purple" type="submit">دریافت رسیدها</button>
  </form>
  <?php if ($result && $result['label'] === 'GET /receipts') echo renderResult($result); ?>
</div>

<!-- ═══════════════════════════════ SECTION 5: انتقال فایل ════════════════════ -->
<div class="section-title full">⑤ انتقال فایل رمزنگاری‌شده (صوت، تصویر، سند)</div>

<!-- File upload single chunk -->
<div class="card">
  <div class="card-header">
    <div class="card-title"><span class="badge POST">POST</span> /files/upload — یک chunk</div>
    <div class="card-desc">
      <b>هدف:</b> آپلود یک فایل کوچک (صوت، تصویر) در یک درخواست.<br>
      <b>سرور چه می‌بیند:</b> فقط blob رمزنگاری‌شده — نام فایل و MIME type هم رمزنگاری‌شده‌اند.<br>
      <b>پاسخ:</b> <code>progress_pct: 100</code> وقتی آپلود کامل شد.<br>
      <b>سقف:</b> 50 MB (در config.php قابل تغییر).
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="file_upload">
    <label>دستگاه فرستنده</label><?= slotSelect('file_from', 'A') ?>
    <label>دستگاه گیرنده</label><?= slotSelect('file_to', 'B', ['B','A','C']) ?>
    <label>file_id <small>(اختیاری)</small></label>
    <input type="text" name="file_id_upload" placeholder="خودکار">
    <button class="btn btn-blue" type="submit">آپلود فایل (شبیه‌سازی)</button>
  </form>
  <?php if ($result && str_contains($result['label'] ?? '', 'chunk)')) echo renderResult($result); ?>
</div>

<!-- File upload multi-chunk -->
<div class="card">
  <div class="card-header">
    <div class="card-title"><span class="badge POST">POST</span> /files/upload — چند chunk (درصد پیشرفت)</div>
    <div class="card-desc">
      <b>هدف:</b> آپلود فایل بزرگ به صورت قطعه‌قطعه — هر chunk یک درخواست جداست.<br>
      <b>درصد پیشرفت:</b> پاسخ هر chunk شامل <code>progress_pct</code> است — اپ می‌تواند progress bar نشان دهد.<br>
      <b>مکانیزم:</b> client یک file_id ثابت برای همه chunk‌ها ارسال می‌کند.<br>
      <b>در این تست:</b> 3 chunk شبیه‌سازی می‌شود.
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="file_upload_chunked">
    <label>دستگاه فرستنده</label><?= slotSelect('chunk_from', 'A') ?>
    <label>دستگاه گیرنده</label><?= slotSelect('chunk_to', 'B', ['B','A','C']) ?>
    <button class="btn btn-yellow" type="submit">آپلود 3-chunk با درصد پیشرفت</button>
  </form>
  <?php if ($result && str_contains($result['label'] ?? '', 'chunked')) echo renderResult($result); ?>
</div>

<!-- List files -->
<div class="card">
  <div class="card-header">
    <div class="card-title"><span class="badge GET">GET</span> /files</div>
    <div class="card-desc">
      <b>هدف:</b> دریافت لیست فایل‌های آپلودشده و در انتظار دریافت.<br>
      <b>فقط فایل‌های کامل:</b> فایلی که آپلودش تمام شده (<code>upload_complete=1</code>) و هنوز ACK نشده.<br>
      <b>اطلاعات:</b> file_id، سایز، تعداد chunk، زمان انقضا.
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="file_list">
    <label>دستگاه گیرنده</label><?= slotSelect('file_list_slot', 'B', ['B','A','C']) ?>
    <button class="btn btn-green" type="submit">لیست فایل‌های در انتظار</button>
  </form>
  <?php if ($result && $result['label'] === 'GET /files (لیست فایل‌های در انتظار)') echo renderResult($result); ?>
</div>

<!-- Download chunk -->
<div class="card">
  <div class="card-header">
    <div class="card-title"><span class="badge GET">GET</span> /files/{id}/chunk/{index}</div>
    <div class="card-desc">
      <b>هدف:</b> دانلود یک chunk از فایل — پاسخ باینری رمزنگاری‌شده است.<br>
      <b>درصد پیشرفت:</b> header <code>X-Progress-Pct</code> درصد کل دانلود را نشان می‌دهد.<br>
      <b>Resume:</b> پشتیبانی از HTTP Range — دانلود قابل ادامه‌دادن است.<br>
      <b>نکته:</b> client هر chunk را جداگانه رمزگشایی می‌کند.
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="file_download">
    <label>دستگاه گیرنده</label><?= slotSelect('dl_slot', 'B', ['B','A','C']) ?>
    <label>file_id</label>
    <input type="text" name="dl_file_id" value="<?= htmlspecialchars($_SESSION['last_file_id'] ?? '') ?>" placeholder="از آپلود یا لیست کپی کنید">
    <label>chunk index <small>(از 0)</small></label>
    <input type="number" name="dl_chunk" value="0" min="0">
    <button class="btn btn-green" type="submit">دانلود chunk</button>
  </form>
  <?php if ($result && str_contains($result['label'] ?? '', '/chunk/')) echo renderResult($result); ?>
</div>

<!-- File ACK -->
<div class="card">
  <div class="card-header">
    <div class="card-title"><span class="badge DELETE">DELETE</span> /files/{id}</div>
    <div class="card-desc">
      <b>هدف:</b> تایید دریافت فایل — فایل از سرور و دیسک حذف می‌شود.<br>
      <b>رسید:</b> مثل پیام، یک رسید برای فرستنده نوشته می‌شود (<code>GET /receipts</code>).<br>
      <b>حذف کامل:</b> همه chunk‌ها از دیسک پاک می‌شوند — سرور چیزی نگه نمی‌دارد.
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="file_ack">
    <label>دستگاه گیرنده</label><?= slotSelect('fack_slot', 'B', ['B','A','C']) ?>
    <label>file_id</label>
    <input type="text" name="fack_id" value="<?= htmlspecialchars($_SESSION['last_file_id'] ?? '') ?>" placeholder="از آپلود یا لیست کپی کنید">
    <button class="btn btn-red" type="submit">تایید دریافت و حذف فایل از سرور</button>
  </form>
  <?php if ($result && str_contains($result['label'] ?? '', 'DELETE /files/')) echo renderResult($result); ?>
</div>

<!-- File E2E -->
<div class="card full">
  <div class="card-header">
    <div class="card-title">تست کامل جریان فایل E2E — ۸ مرحله</div>
    <div class="card-desc">
      <b>①</b> ثبت A و B &nbsp;→&nbsp;
      <b>②</b> A فایل را در 2 chunk آپلود می‌کند (با درصد پیشرفت) &nbsp;→&nbsp;
      <b>③</b> B لیست فایل‌های دریافتی را می‌گیرد &nbsp;→&nbsp;
      <b>④</b> B هر chunk را دانلود می‌کند &nbsp;→&nbsp;
      <b>⑤</b> B فایل را ACK می‌کند — از سرور و دیسک حذف می‌شود &nbsp;→&nbsp;
      <b>⑥</b> A رسید دریافت فایل را می‌بیند &nbsp;→&nbsp;
      <b>⑦</b> تایید خالی بودن صندوق B
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="full_flow_file">
    <button class="btn btn-purple" type="submit" style="max-width:320px">اجرای تست کامل جریان فایل</button>
  </form>
  <?php if ($result && str_contains($result['label'] ?? '', 'جریان فایل E2E')) echo renderResult($result); ?>
</div>

<!-- ═══════════════════════════════ SECTION 6: تست‌های امنیتی ═════════════════ -->
<div class="section-title full">⑤ تست‌های امنیتی</div>

<!-- Auth fail -->
<div class="card">
  <div class="card-header">
    <div class="card-title">تست احراز هویت نامعتبر</div>
    <div class="card-desc">
      <b>هدف:</b> تایید اینکه token اشتباه → سرور دسترسی را رد می‌کند.<br>
      <b>انتظار:</b> <code>401 {"error":"invalid_token"}</code><br>
      <b>مکانیزم:</b> سرور SHA-256 هر token را با مقادیر ذخیره‌شده مقایسه می‌کند — token اصلی هرگز ذخیره نمی‌شود.
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="auth_fail">
    <button class="btn btn-gray" type="submit">ارسال token جعلی (باید 401 بگیرد)</button>
  </form>
  <?php if ($result && str_contains($result['label'], '401')) echo renderResult($result); ?>
</div>

<!-- Rate limit test -->
<div class="card">
  <div class="card-header">
    <div class="card-title">تست Rate Limiting</div>
    <div class="card-desc">
      <b>هدف:</b> تایید محدودیت تعداد درخواست — حداکثر 10 ثبت‌نام در ساعت از یک IP.<br>
      <b>انتظار:</b> درخواست‌های ابتدایی <code>201</code>، بعد از اتمام سهمیه → <code>429</code>.<br>
      <b>هدر پاسخ:</b> <code>Retry-After</code> نشان می‌دهد چند ثانیه باید صبر کرد.
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="rate_test">
    <button class="btn btn-gray" type="submit">ارسال 12 درخواست متوالی (تا رسیدن به 429)</button>
  </form>
  <?php if ($result && str_contains($result['label'], 'Rate Limit')) echo renderResult($result); ?>
</div>

<!-- ═══════════════════════════════ SECTION 6: تست کامل ══════════════════════ -->
<div class="section-title full">⑥ تست کامل جریان E2E</div>

<!-- Full E2E -->
<div class="card full">
  <div class="card-header">
    <div class="card-title">تست کامل جریان E2E — ۱۱ مرحله خودکار</div>
    <div class="card-desc">
      جریان کامل زندگی یک پیام از صفر:<br>
      <b>①</b> ثبت A &nbsp;→&nbsp;
      <b>②</b> ثبت B &nbsp;→&nbsp;
      <b>③</b> A کلید B را می‌گیرد &nbsp;→&nbsp;
      <b>④</b> A پیام می‌فرستد &nbsp;→&nbsp;
      <b>⑤</b> B پیام را می‌گیرد &nbsp;→&nbsp;
      <b>⑥</b> B تایید می‌کند (ACK) &nbsp;→&nbsp;
      <b>⑦</b> A رسید را می‌بیند &nbsp;→&nbsp;
      <b>⑧</b> صندوق B خالی است &nbsp;→&nbsp;
      <b>⑨</b> A کلید خود را می‌چرخاند &nbsp;→&nbsp;
      <b>⑩</b> B کلید جدید A را می‌گیرد &nbsp;→&nbsp;
      <b>⑪</b> تست جلوگیری از re-registration
    </div>
  </div>
  <form method="post">
    <input type="hidden" name="action" value="full_flow">
    <button class="btn btn-purple" type="submit" style="max-width:320px">اجرای تست کامل E2E</button>
  </form>
  <?php if ($result && str_contains($result['label'], 'E2E')) echo renderResult($result); ?>
</div>

<!-- ═══════════════════════════════ SECTION 7: نکات امنیتی ═══════════════════ -->
<div class="section-title full">⑦ خلاصه معماری امنیتی</div>
<div class="card full">
  <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px;font-size:11px;line-height:2">
    <div>
      <div style="color:#86efac;margin-bottom:6px;font-weight:700">✅ چی درست پیاده‌سازی شده</div>
      <div>token هیچوقت در DB ذخیره نمی‌شود — فقط SHA-256</div>
      <div>re-registration بدون token قبلی ممنوع (409)</div>
      <div>چرخش کلید فقط با احراز هویت (PUT /devices/update)</div>
      <div>سرور محتوای ciphertext را نمی‌خواند — blob کامل</div>
      <div>پیام بعد از ACK فوراً حذف می‌شود (نه soft-delete)</div>
      <div>رسیدها بعد از تحویل به فرستنده حذف می‌شوند</div>
      <div>پیام‌های منقضی (24h) در هر request پاکسازی می‌شوند</div>
      <div>Rate limiting per-IP برای register/send/pull/pubkey</div>
      <div>DB خارج از public_html (در cPanel)</div>
    </div>
    <div>
      <div style="color:#fde68a;margin-bottom:6px;font-weight:700">⚠️ برای محیط تولید</div>
      <div>سرور SQLite رمزنگاری نشده — فقط کلاینت اندروید SQLCipher دارد</div>
      <div>Rate limit در PHP است — در تولید nginx/Cloudflare را ترجیح بدهید</div>
      <div>برای حجم بالا WAL mode روی SQLite فعال کنید (اگر NFS نباشد)</div>
      <div>HTTPS اجباری — بدون TLS token در transit قابل رهگیری است</div>
      <div>deviceId توسط کلاینت تولید می‌شود — در تولید UUID v4 کافی است</div>
    </div>
  </div>
</div>

</div><!-- .grid -->
</div><!-- .container -->

<div id="toast">کپی شد!</div>
<script>
document.addEventListener('DOMContentLoaded', function() {
  var r = document.querySelector('.result-box');
  if (r) r.scrollIntoView({behavior:'smooth', block:'nearest'});
});
</script>
</body>
</html>
