<?php

class Router {
    private array $routes = [];
    private string $prefix;

    public function __construct(string $prefix = '') {
        $this->prefix = rtrim($prefix, '/');
    }

    public function add(string $method, string $path, callable $handler): void {
        $this->routes[] = [
            'method'  => strtoupper($method),
            'pattern' => $this->prefix . $path,
            'handler' => $handler,
        ];
    }

    public function dispatch(): void {
        $method = $_SERVER['REQUEST_METHOD'];
        $uri    = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
        
        // XAMPP subdirectory: strip the subfolder path from URI
        $config = require __DIR__ . '/../config.php';
        if ($config['env'] === 'xampp') {
            $scriptName = $_SERVER['SCRIPT_NAME'] ?? '';
            $scriptDir  = dirname($scriptName);
            if ($scriptDir !== '/' && str_starts_with($uri, $scriptDir)) {
                $uri = substr($uri, strlen($scriptDir));
            }
        }
        
        $uri = '/' . trim($uri, '/');

        foreach ($this->routes as $route) {
            $pattern = $this->toRegex($route['pattern']);
            if ($route['method'] === $method && preg_match($pattern, $uri, $matches)) {
                $params = array_filter($matches, 'is_string', ARRAY_FILTER_USE_KEY);
                ($route['handler'])($params);
                return;
            }
        }

        http_response_code(404);
        echo json_encode(['error' => 'not_found']);
    }

    private function toRegex(string $path): string {
        $pattern = preg_replace('/\{(\w+)\}/', '(?P<$1>[^/]+)', $path);
        return '#^' . $pattern . '$#';
    }
}
