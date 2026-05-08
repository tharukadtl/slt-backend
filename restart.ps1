# restart.ps1 — Kill any process on port 8080, then start the Spring Boot server.
# Usage: .\restart.ps1

$port = 8080
$log  = "logs\slt-fieldops.log"

# Kill whatever is holding port 8080
$pid8080 = (netstat -ano | Select-String ":$port\s" | Where-Object { $_ -match 'LISTENING' } |
            ForEach-Object { ($_ -split '\s+')[-1] } | Select-Object -First 1)

if ($pid8080) {
    Write-Host "Killing PID $pid8080 on port $port..."
    taskkill /F /PID $pid8080 | Out-Null
    Start-Sleep -Milliseconds 500
} else {
    Write-Host "Port $port is free."
}

# Ensure logs directory exists
if (-not (Test-Path "logs")) { New-Item -ItemType Directory -Path "logs" | Out-Null }

# Start server in background, output to log file
Write-Host "Starting server... (log: $log)"
Start-Process -FilePath "cmd.exe" `
    -ArgumentList "/c", ".\mvnw.cmd spring-boot:run > $log 2>&1" `
    -NoNewWindow

# Wait up to 60 seconds for startup
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    if (Test-Path $log) {
        $content = Get-Content $log -Raw -ErrorAction SilentlyContinue
        if ($content -match "Started Application") {
            Write-Host "Server is up on port $port."
            exit 0
        }
        if ($content -match "APPLICATION FAILED|BUILD FAILURE") {
            Write-Host "Server failed to start. Check $log for details."
            exit 1
        }
    }
}
Write-Host "Timed out waiting for server. Check $log for details."
exit 1
