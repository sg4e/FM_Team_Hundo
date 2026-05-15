param(
    [switch]$Clean
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$TexFile = "fm-team-hundo.tex"
$PdfFile = "fm-team-hundo.pdf"

function Remove-BuildArtifacts {
    param([string]$Directory)

    $extensions = @(
        "*.aux", "*.fdb_latexmk", "*.fls", "*.log", "*.nav", "*.out",
        "*.snm", "*.synctex.gz", "*.toc", "*.vrb"
    )

    foreach ($pattern in $extensions) {
        Get-ChildItem -Path $Directory -Filter $pattern -File -ErrorAction SilentlyContinue |
            Remove-Item -Force
    }
}

function Get-Tool {
    param([string]$Name)
    return Get-Command $Name -ErrorAction SilentlyContinue
}

Push-Location $ScriptDir
try {
    if ($Clean) {
        Remove-BuildArtifacts -Directory $ScriptDir
        if (Test-Path $PdfFile) {
            Remove-Item $PdfFile -Force
        }
    }

    $latexmk = Get-Tool "latexmk"
    $pdflatex = Get-Tool "pdflatex"

    if ($latexmk) {
        & $latexmk.Source -pdf -interaction=nonstopmode -halt-on-error -file-line-error $TexFile
        if ($LASTEXITCODE -ne 0) {
            throw "latexmk failed with exit code $LASTEXITCODE."
        }
    }
    elseif ($pdflatex) {
        for ($pass = 1; $pass -le 2; $pass++) {
            & $pdflatex.Source -interaction=nonstopmode -halt-on-error -file-line-error $TexFile
            if ($LASTEXITCODE -ne 0) {
                throw "pdflatex failed on pass $pass with exit code $LASTEXITCODE."
            }
        }
    }
    else {
        throw "No LaTeX compiler found. Install latexmk or pdflatex, then run presentation/build.ps1 again."
    }

    if (-not (Test-Path $PdfFile)) {
        throw "Build completed but $PdfFile was not created."
    }

    Write-Host "Built $((Resolve-Path $PdfFile).Path)"
}
finally {
    Pop-Location
}
