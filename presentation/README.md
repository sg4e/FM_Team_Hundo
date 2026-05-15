# FM Team Hundo Presentation

This directory contains a Beamer presentation and speaker script for a roughly 10-minute, non-technical overview of the FM Team Hundo project.

## Files

- `fm-team-hundo.tex`: Beamer source with a self-contained TikZ architecture diagram.
- `speaker-script.md`: Per-slide talking points and timing.
- `build.ps1`: PowerShell build script for producing `fm-team-hundo.pdf`.

## Build

From the repository root:

```powershell
.\presentation\build.ps1
```

The script prefers `latexmk` and falls back to running `pdflatex` twice. Use this to remove generated files first:

```powershell
.\presentation\build.ps1 -Clean
```

If the script cannot find a LaTeX compiler, install a TeX distribution such as MiKTeX or TeX Live and rerun it.
