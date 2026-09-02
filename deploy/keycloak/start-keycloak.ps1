# Starts the local Keycloak with the SPI configuration it needs.
#
# Run it from Windows, or from WSL with:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File <this file>
#
# WHY A SCRIPT RATHER THAN TWO COMMANDS. The credential SPI signs its call to ipie-iam-service with
# a shared key and has no usable default for it, deliberately - an empty key fails closed so a login
# can never succeed unverified. Starting Keycloak without those variables produces
# "unknown_error" from the token endpoint and no request reaching iam at all, which reads as a
# platform fault and is not one. Loading the whole env file removes the chance of setting one of the
# three and missing the others.
#
# --http-host=0.0.0.0 matters too: dev mode otherwise binds localhost only, so WSL cannot reach
# Keycloak and every token call has to be driven from a container instead.
#
# Console output is redirected to a log file because kc.bat logs to the console only, and a stack
# trace nobody can read afterwards is the difference between diagnosing this in a minute and an hour.

param(
    [string]$KeycloakHome = 'D:\keycloak-26.6.3',
    [string]$EnvFile = 'D:\MasterCode\IpieMicroservicesCurrent\ipie-platform-mca\deploy\keycloak\env\spi.dev.env',
    [string]$LogFile = 'D:\keycloak-26.6.3\keycloak-console.log'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path "$KeycloakHome\bin\kc.bat")) { throw "kc.bat not found under $KeycloakHome" }
if (-not (Test-Path $EnvFile)) { throw "SPI environment file not found: $EnvFile" }

# Refuse to start a second instance: dev mode keeps its realm in an H2 file, and a second JVM fails
# on the lock in a way that looks like corruption.
$running = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*keycloak*' }
if ($running) {
    Write-Output "Keycloak is already running (PID $($running.ProcessId)). Stop it first."
    exit 1
}

$loaded = @()
Get-Content $EnvFile | Where-Object { $_ -and $_ -notmatch '^\s*#' } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    Set-Item -Path ("Env:" + $name.Trim()) -Value $value.Trim()
    $loaded += $name.Trim()
}
Write-Output ("Loaded {0} SPI settings: {1}" -f $loaded.Count, ($loaded -join ', '))

if (Test-Path $LogFile) { Remove-Item $LogFile -Force }

Start-Process -FilePath 'cmd.exe' `
    -ArgumentList '/c', "`"$KeycloakHome\bin\kc.bat`" start-dev --http-host=0.0.0.0 > `"$LogFile`" 2>&1" `
    -WorkingDirectory $KeycloakHome `
    -WindowStyle Minimized

Write-Output "Keycloak starting. Console output: $LogFile"
