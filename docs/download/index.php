<?php
/**
 * 短信转发助手 - 简单下载目录
 */

$title = '短信转发助手 - 下载中心';
$files = [];

// 获取目录中的 APK 文件
$dir = __DIR__;
if (is_dir($dir)) {
    $items = scandir($dir);
    foreach ($items as $item) {
        if ($item === '.' || $item === '..' || $item === 'index.php') {
            continue;
        }
        $path = $dir . '/' . $item;
        if (is_file($path)) {
            $ext = strtolower(pathinfo($item, PATHINFO_EXTENSION));
            if ($ext === 'apk') {
                $files[] = [
                    'name' => $item,
                    'size' => filesize($path),
                    'time' => filemtime($path),
                    'url' => $item
                ];
            }
        }
    }
}

// 按修改时间倒序排列
usort($files, function($a, $b) {
    return $b['time'] - $a['time'];
});

// 格式化文件大小
function formatSize($bytes) {
    $units = ['B', 'KB', 'MB', 'GB'];
    $bytes = max($bytes, 0);
    $pow = floor(($bytes ? log($bytes) : 0) / log(1024));
    $pow = min($pow, count($units) - 1);
    $bytes /= pow(1024, $pow);
    return round($bytes, 2) . ' ' . $units[$pow];
}
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title><?php echo $title; ?></title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Noto Sans SC', sans-serif;
            background: linear-gradient(135deg, #667EEA 0%, #764BA2 100%);
            min-height: 100vh;
            padding: 40px 20px;
        }
        .container {
            max-width: 700px;
            margin: 0 auto;
        }
        .header {
            text-align: center;
            margin-bottom: 40px;
            color: white;
        }
        .header h1 {
            font-size: 32px;
            margin-bottom: 8px;
        }
        .header p {
            opacity: 0.9;
            font-size: 15px;
        }
        .card {
            background: white;
            border-radius: 20px;
            padding: 32px;
            box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
        }
        .file-list {
            display: flex;
            flex-direction: column;
            gap: 12px;
        }
        .file-item {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 16px 20px;
            background: #F8F9FF;
            border-radius: 12px;
            transition: all 0.3s ease;
        }
        .file-item:hover {
            transform: translateY(-2px);
            box-shadow: 0 8px 24px rgba(102, 126, 234, 0.15);
            background: #F0F4FF;
        }
        .file-info {
            display: flex;
            align-items: center;
            gap: 14px;
        }
        .file-icon {
            width: 44px;
            height: 44px;
            background: linear-gradient(135deg, #667EEA 0%, #764BA2 100%);
            border-radius: 10px;
            display: flex;
            align-items: center;
            justify-content: center;
            color: white;
            font-size: 20px;
        }
        .file-details {
            display: flex;
            flex-direction: column;
        }
        .file-name {
            font-weight: 600;
            color: #1F2937;
            font-size: 15px;
        }
        .file-meta {
            font-size: 13px;
            color: #9CA3AF;
            margin-top: 2px;
        }
        .download-btn {
            display: inline-flex;
            align-items: center;
            gap: 8px;
            background: linear-gradient(135deg, #667EEA 0%, #764BA2 100%);
            color: white;
            padding: 10px 20px;
            border-radius: 10px;
            font-size: 14px;
            font-weight: 600;
            text-decoration: none;
            transition: all 0.3s ease;
            box-shadow: 0 4px 12px rgba(102, 126, 234, 0.35);
        }
        .download-btn:hover {
            transform: translateY(-2px);
            box-shadow: 0 6px 16px rgba(102, 126, 234, 0.45);
        }
        .empty-state {
            text-align: center;
            padding: 40px 20px;
            color: #9CA3AF;
        }
        .empty-icon {
            font-size: 48px;
            margin-bottom: 12px;
        }
        .footer {
            text-align: center;
            margin-top: 32px;
            color: white;
            opacity: 0.9;
        }
        .footer a {
            color: white;
            text-decoration: none;
        }
        .footer a:hover {
            text-decoration: underline;
        }
        @media (max-width: 640px) {
            .header h1 {
                font-size: 24px;
            }
            .card {
                padding: 24px 20px;
            }
            .file-item {
                flex-direction: column;
                align-items: flex-start;
                gap: 12px;
            }
            .download-btn {
                width: 100%;
                justify-content: center;
            }
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>📥 短信转发助手</h1>
            <p>国内高速下载通道</p>
        </div>
        
        <div class="card">
            <?php if (!empty($files)): ?>
                <div class="file-list">
                    <?php foreach ($files as $file): ?>
                        <div class="file-item">
                            <div class="file-info">
                                <div class="file-icon">📱</div>
                                <div class="file-details">
                                    <div class="file-name"><?php echo htmlspecialchars($file['name']); ?></div>
                                    <div class="file-meta">
                                        <?php echo formatSize($file['size']); ?> · 
                                        <?php echo date('Y-m-d H:i', $file['time']); ?>
                                    </div>
                                </div>
                            </div>
                            <a href="<?php echo htmlspecialchars($file['url']); ?>" class="download-btn">
                                <span>⬇️</span>
                                <span>下载</span>
                            </a>
                        </div>
                    <?php endforeach; ?>
                </div>
            <?php else: ?>
                <div class="empty-state">
                    <div class="empty-icon">📦</div>
                    <p>暂无文件，请稍后再来</p>
                </div>
            <?php endif; ?>
        </div>
        
        <div class="footer">
            <a href="https://smsforwarder.cn/">← 返回官方网站</a>
        </div>
    </div>
</body>
</html>
