# Query the native window from a different process through Windows UI Automation.
param(
    [Parameter(Mandatory = $true)]
    [string]$Executable
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes

# The segments and stepper buttons appear together on the first screen.
$expected = @('First', 'Second', 'Third', 'Less', 'More')
$buttonType = [System.Windows.Automation.ControlType]::Button.Id
$exe = (Resolve-Path -LiteralPath $Executable).Path
$env:DXC_D3D12_WARP = '1'
$sample = Start-Process -FilePath $exe -PassThru
try {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $observed = @()
    $missing = $expected
    do {
        $sample.Refresh()
        if ($sample.HasExited) {
            throw "Sample exited before the UI Automation check (exit $($sample.ExitCode))."
        }
        if ($sample.MainWindowHandle -ne [IntPtr]::Zero) {
            try {
                $root = [System.Windows.Automation.AutomationElement]::FromHandle($sample.MainWindowHandle)
                $nodes = $root.FindAll(
                    [System.Windows.Automation.TreeScope]::Descendants,
                    [System.Windows.Automation.Condition]::TrueCondition
                )
                $observed = @(foreach ($node in $nodes) {
                    $current = $node.Current
                    if ($current.ProcessId -eq $sample.Id) {
                        [pscustomobject]@{
                            Name = $current.Name
                            Type = $current.ControlType.Id
                            Role = $current.ControlType.ProgrammaticName
                        }
                    }
                })
            } catch [System.Windows.Automation.ElementNotAvailableException] {
                # The renderer might replace its snapshot while UI Automation reads it.
                $observed = @()
            }
            $missing = @($expected | Where-Object {
                $name = $_
                -not ($observed | Where-Object { $_.Name -ceq $name -and $_.Type -eq $buttonType })
            })
            if ($missing.Count -eq 0) {
                foreach ($name in $expected) { Write-Output "UI Automation reported Button '$name'." }
                return
            }
        }
        Start-Sleep -Milliseconds 250
    } while ($timer.Elapsed.TotalSeconds -lt 45)

    $observed | Format-Table Name, Role -AutoSize | Out-String | Write-Output
    if ($sample.MainWindowHandle -eq [IntPtr]::Zero) {
        throw 'The sample did not open a window before the UI Automation check timed out.'
    }
    throw "UI Automation did not report the expected Button controls: $($missing -join ', ')."
} finally {
    $sample.Refresh()
    if (-not $sample.HasExited) { Stop-Process -InputObject $sample -Force }
}
