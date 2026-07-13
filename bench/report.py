#!/usr/bin/env python3
import json
import sys
import glob
import math
import os
from collections import defaultdict
from typing import List, Dict, Any

MIN_SAMPLES = 20

def percentile(data: List[float], p: float) -> float:
    if not data:
        return 0.0
    data_sorted = sorted(data)
    k = (len(data_sorted) - 1) * p
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return data_sorted[int(k)]
    d0 = data_sorted[int(f)]
    d1 = data_sorted[int(c)]
    return d0 + (d1 - d0) * (k - f)

def format_cell(data: List[float], p: float, is_mem=False) -> str:
    if len(data) < MIN_SAMPLES:
        return "insufficient samples*"
    val = percentile(data, p)
    if is_mem:
        return f"{val / (1024 * 1024):.1f} MB"
    return f"{val:.0f} ms"

def main():
    if len(sys.argv) < 3 or sys.argv[1] == '--help':
        print("Usage: python report.py <results_dir> --out <output_file>")
        sys.exit(1)
        
    results_dir = sys.argv[1]
    out_file = None
    if '--out' in sys.argv:
        out_idx = sys.argv.index('--out')
        if out_idx + 1 < len(sys.argv):
            out_file = sys.argv[out_idx + 1]

    # Read all JSONL files
    all_data = []
    for filepath in glob.glob(os.path.join(results_dir, '*.jsonl')):
        with open(filepath, 'r') as f:
            for line in f:
                if line.strip():
                    try:
                        all_data.append(json.loads(line))
                    except:
                        pass
                        
    # Filter emulators
    data = [d for d in all_data if not d.get('isEmulator', False)]
    if len(all_data) > len(data):
        print(f"Filtered {len(all_data) - len(data)} emulator runs.")

    devices = defaultdict(list)
    for d in data:
        model = d.get('deviceModel', 'Unknown')
        os_ver = d.get('osVersion', '')
        key = f"{model} (OS {os_ver})"
        devices[key].append(d)

    # Generate Markdown
    md = []
    md.append("## On-Device AI Benchmark Results\n")
    
    # Table 1: Metrics Table (p90)
    md.append("### Primary Metrics (p90)\n")
    md.append("| Device | Cold Init | First Token | Complete | Peak Memory | Fallback Rate |")
    md.append("|---|---|---|---|---|---|")
    
    footnotes_needed = False
    
    for device, runs in devices.items():
        cold_inits = [r['initEndMs'] - r['initStartMs'] for r in runs if not r['isWarm']]
        first_tokens = [r['firstTokenMs'] - r['generateStartMs'] for r in runs if r['firstTokenMs'] > 0]
        completes = [r['completeMs'] - r['generateStartMs'] for r in runs if r['completeMs'] > 0]
        peak_mems = [r['peakMemoryBytes'] for r in runs]
        
        fallback_count = sum(1 for r in runs if r.get('fallbackTriggered', False))
        fallback_rate = f"{(fallback_count / len(runs)) * 100:.1f}%" if runs else "N/A"
        
        cold_cell = format_cell(cold_inits, 0.90)
        ft_cell = format_cell(first_tokens, 0.90)
        comp_cell = format_cell(completes, 0.90)
        mem_cell = format_cell(peak_mems, 0.90, is_mem=True)
        
        if "insufficient samples*" in [cold_cell, ft_cell, comp_cell, mem_cell]:
            footnotes_needed = True
            
        md.append(f"| {device} | {cold_cell} | {ft_cell} | {comp_cell} | {mem_cell} | {fallback_rate} |")
        
    md.append("\n")
    
    # Table 2: Variance Table (p50 / p90 / p99)
    md.append("### Completion Time Variance\n")
    md.append("| Device | p50 | p90 | p99 |")
    md.append("|---|---|---|---|")
    
    for device, runs in devices.items():
        completes = [r['completeMs'] - r['generateStartMs'] for r in runs if r['completeMs'] > 0]
        p50_cell = format_cell(completes, 0.50)
        p90_cell = format_cell(completes, 0.90)
        
        # p99 needs at least 100 samples
        if len(completes) < 100:
            p99_cell = "insufficient samples*"
            footnotes_needed = True
        else:
            p99_cell = f"{percentile(completes, 0.99):.0f} ms"
            
        md.append(f"| {device} | {p50_cell} | {p90_cell} | {p99_cell} |")

    if footnotes_needed:
        md.append("\n* *Insufficient samples: requires minimum 20 iterations for p50/p90, 100 for p99.*")
        
    output = "\n".join(md)
    print(output)
    
    if out_file:
        with open(out_file, 'w') as f:
            f.write(output)
            print(f"\nReport written to {out_file}")

if __name__ == '__main__':
    main()
