# Inspect the native owner-checks window from a separate UI Automation client.
param(
    [Parameter(Mandatory = $true)]
    [string]$Executable
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes

function nfr8_windows_owner_checks_exposes_control_names_and_roles {
    param([Diagnostics.Process]$Sample)

    $button = [System.Windows.Automation.ControlType]::Button
    $toggle = [System.Windows.Automation.TogglePattern]::Pattern
    # Windows exposes a switch as a Button with the Toggle pattern.
    $expected = [ordered]@{
        'Test button' = $button
        'Test switch' = $button
        'Test checkbox' = [System.Windows.Automation.ControlType]::CheckBox
        'Test slider' = [System.Windows.Automation.ControlType]::Slider
        'Test text field' = [System.Windows.Automation.ControlType]::Edit
    }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $opened = $false
    $observed = @()
    $missing = @($expected.Keys)
    do {
        $Sample.Refresh()
        if ($Sample.HasExited) {
            throw "Owner checks exited before the UI Automation check (exit $($Sample.ExitCode))."
        }
        if ($Sample.MainWindowHandle -ne [IntPtr]::Zero) {
            try {
                $root = [System.Windows.Automation.AutomationElement]::FromHandle($Sample.MainWindowHandle)
                $nodes = $root.FindAll(
                    [System.Windows.Automation.TreeScope]::Descendants,
                    [System.Windows.Automation.Condition]::TrueCondition
                )
                $observed = @(foreach ($node in $nodes) {
                    $current = $node.Current
                    if ($current.ProcessId -eq $Sample.Id) {
                        [pscustomobject]@{
                            Name = $current.Name
                            Type = $current.ControlType
                            Element = $node
                        }
                    }
                })
                if (-not $opened) {
                    $page = $observed | Where-Object { $_.Name -ceq 'Screen reader' -and $_.Type.Id -eq $button.Id } | Select-Object -First 1
                    if ($page) {
                        $pattern = $null
                        if (-not $page.Element.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pattern)) {
                            throw 'The Screen reader page button lacks the UI Automation Invoke pattern.'
                        }
                        $pattern.Invoke()
                        $opened = $true
                    }
                } else {
                    $missing = @(foreach ($name in $expected.Keys) {
                        $type = $expected[$name]
                        $control = $observed | Where-Object { $_.Name -ceq $name -and $_.Type.Id -eq $type.Id } | Select-Object -First 1
                        if (-not $control) {
                            "$name ($($type.ProgrammaticName))"
                        } elseif ($name -eq 'Test switch') {
                            $pattern = $null
                            if (-not $control.Element.TryGetCurrentPattern($toggle, [ref]$pattern)) {
                                "$name ($($type.ProgrammaticName) with Toggle)"
                            }
                        }
                    })
                    if ($missing.Count -eq 0) {
                        foreach ($name in $expected.Keys) {
                            Write-Output "UI Automation reported '$name' as $($expected[$name].ProgrammaticName)."
                        }
                        return
                    }
                }
            } catch [System.Windows.Automation.ElementNotAvailableException] {
                # A redraw can replace the snapshot during a query.
            }
        }
        Start-Sleep -Milliseconds 250
    } while ($timer.Elapsed.TotalSeconds -lt 45)

    $observed | Select-Object Name, @{Name='Role'; Expression={$_.Type.ProgrammaticName}} | Format-Table -AutoSize | Out-String | Write-Output
    if (-not $opened) {
        throw 'The owner checks window did not expose the Screen reader page button.'
    }
    throw "UI Automation did not report the expected controls: $($missing -join ', ')."
}

$exe = (Resolve-Path -LiteralPath $Executable).Path
$env:DXC_D3D12_WARP = '1'
$sample = Start-Process -FilePath $exe -PassThru
try {
    nfr8_windows_owner_checks_exposes_control_names_and_roles -Sample $sample
} finally {
    $sample.Refresh()
    if (-not $sample.HasExited) { Stop-Process -InputObject $sample -Force }
}
