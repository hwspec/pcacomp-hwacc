#!/usr/bin/env python3
"""
simulate.py - Generate Verilog from PCA JSON configuration files and optionally test

This script provides a simple interface to generate Verilog using PCA JSON
configuration files without requiring familiarity with sbt.

Usage:
    python3 simulate.py [config.json] [--test]
    python3 simulate.py configs/default.json
    python3 simulate.py configs/default.json --test  # Also generate test vectors and run tests
    
    If no config file is provided, defaults to configs/default.json

The script will:
1. Validate that the config file exists
2. Run sbt to generate Verilog using the JSON loader
3. (If --test) Export test vectors from Scala test data generator
4. (If --test) Run cocotb testbench to verify generated Verilog

Examples:
    python3 simulate.py                    # Uses configs/default.json by default
    python3 simulate.py configs/default.json
    python3 simulate.py configs/default.json --test  # Generate and test
    python3 simulate.py configs/medium.json --test
"""

import sys
import os
import subprocess
import argparse
import json
import glob
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(
        description='Generate Verilog from PCA JSON configuration files',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument(
        'config',
        type=str,
        nargs='?',
        default='configs/default.json',
        help='Path to JSON configuration file (default: configs/default.json)'
    )
    parser.add_argument(
        '--verbose', '-v',
        action='store_true',
        help='Show verbose sbt output'
    )
    parser.add_argument(
        '--test', '-t',
        action='store_true',
        help='Export test vectors and run cocotb testbench after generating Verilog'
    )
    
    args = parser.parse_args()
    
    # Validate config file exists
    config_path = Path(args.config)
    if not config_path.exists():
        print(f"Error: Config file not found: {args.config}", file=sys.stderr)
        sys.exit(1)
    
    # Normalize the path to handle relative/absolute paths
    config_path = config_path.resolve()
    
    # Convert Windows path to format sbt can handle
    config_str = str(config_path)
    if sys.platform == 'win32':
        # On Windows, convert backslashes to forward slashes for sbt
        config_str = config_str.replace('\\', '/')
    
    print(f"Loading configuration from: {config_path}")
    print(f"Generating Verilog using PCACompBlockJson...")
    print()
    
    # Build sbt command
    sbt_command = f'runMain pca.PCACompBlockJson "{config_str}"'
    
    try:
        # Run sbt command
        # sbt output is verbose, so we capture it and show important parts
        result = subprocess.run(
            ['sbt', sbt_command],
            check=True,
            capture_output=True,
            text=True
        )
        
        # Print output, filtering for relevant information
        if args.verbose:
            print(result.stdout)
        else:
            # Show only important lines (config loading, errors, and final results)
            for line in result.stdout.split('\n'):
                if any(keyword in line.lower() for keyword in [
                    'loading config', 'config loaded', 'error', 
                    'w=', 'h=', 'pxbw=', 'm=', 'encbw=', 'nblocks=',
                    'seed=', 'nonegative=', 'generated'
                ]) or line.strip().startswith('[error]'):
                    print(line)
        
        # Always show stderr if present
        if result.stderr:
            print(result.stderr, file=sys.stderr)
                    
    except subprocess.CalledProcessError as e:
        print(f"Error: sbt command failed", file=sys.stderr)
        if hasattr(e, 'stdout') and e.stdout:
            print(e.stdout, file=sys.stderr)
        if hasattr(e, 'stderr') and e.stderr:
            print(e.stderr, file=sys.stderr)
        sys.exit(1)
    except FileNotFoundError:
        print("Error: sbt not found. Please ensure sbt is installed and in your PATH.", file=sys.stderr)
        print("See https://www.scala-sbt.org/download.html for installation instructions.", file=sys.stderr)
        sys.exit(1)
    
    print()
    print("✓ Verilog generation completed successfully!")
    print(f"  Output files should be in the 'generated' directory.")
    
    # If --test flag is set, export test vectors and run tests
    if args.test:
        print()
        print("=" * 60)
        print("Exporting test vectors...")
        print("=" * 60)
        
        # Export test vectors
        test_vectors_path = "test_vectors.json"
        export_command = f'test:runMain pca.ExportTestVectors "{config_str}" {test_vectors_path}'
        
        try:
            export_result = subprocess.run(
                ['sbt', export_command],
                check=True,
                capture_output=True,
                text=True
            )
            
            if args.verbose:
                print(export_result.stdout)
            else:
                for line in export_result.stdout.split('\n'):
                    if any(keyword in line.lower() for keyword in [
                        'generating test vectors', 'test vectors exported', 'config:', 'blocks:', 'components'
                    ]):
                        print(line)
            
            if not os.path.exists(test_vectors_path):
                print(f"Error: Test vectors file was not created: {test_vectors_path}", file=sys.stderr)
                sys.exit(1)
            
            print(f"✓ Test vectors exported to: {test_vectors_path}")
            
        except subprocess.CalledProcessError as e:
            print(f"Error: Failed to export test vectors", file=sys.stderr)
            if hasattr(e, 'stdout') and e.stdout:
                print(e.stdout, file=sys.stderr)
            if hasattr(e, 'stderr') and e.stderr:
                print(e.stderr, file=sys.stderr)
            sys.exit(1)
        
        print()
        print("=" * 60)
        print("Running cocotb testbench...")
        print("=" * 60)
        
        # Load test vectors to find the module name
        with open(test_vectors_path, 'r') as f:
            test_data = json.load(f)
        
        cfg = test_data['config']
        # Derive module name from config (matches Scala naming)
        h = cfg['h']
        w = cfg['w']
        nblocks = cfg['nblocks']
        width = w // nblocks
        pxbw = cfg['pxbw']
        encbw = cfg['encbw']
        m = cfg['m']
        
        module_name = f"PCACompBlock_nrows{h}_ncols{w}_nblocks{nblocks}_w{width}_pxbw{pxbw}_iembw{encbw}_npcs{m}"
        verilog_file = f"generated/{module_name}.sv"
        
        if not os.path.exists(verilog_file):
            print(f"Error: Generated Verilog file not found: {verilog_file}", file=sys.stderr)
            print("  Make sure Verilog generation completed successfully.", file=sys.stderr)
            sys.exit(1)
        
        # Find all SRAM files needed
        sram_files = glob.glob("generated/SRAM1RW__*.sv") + glob.glob("generated/mem_*.sv") + glob.glob("generated/ram_*.sv")
        verilog_sources = [verilog_file] + sram_files
        
        # Check if cocotb is available
        try:
            result = subprocess.run(['cocotb-config', '--version'], 
                                  capture_output=True, text=True, check=True)
            print(f"Using cocotb: {result.stdout.strip()}")
        except (subprocess.CalledProcessError, FileNotFoundError):
            print("Warning: cocotb not found. Skipping testbench execution.", file=sys.stderr)
            print("  Install cocotb with: pip install cocotb cocotb-bus", file=sys.stderr)
            print("  Test vectors are ready for manual testing.", file=sys.stderr)
            return
        
        # Create a simple test runner script or use cocotb directly
        print(f"Running test for module: {module_name}")
        print(f"Verilog file: {verilog_file}")
        
        # Run the test using the Makefile in pca_compblock_cocotb directory
        test_dir = Path("pca_compblock_cocotb")
        if not test_dir.exists():
            print(f"Warning: pca_compblock_cocotb directory not found. Skipping test execution.", file=sys.stderr)
            return
        
        env = os.environ.copy()
        env['TOPLEVEL'] = module_name
        env['VERILOG_SOURCES'] = ' '.join(verilog_sources)
        
        try:
            test_result = subprocess.run(
                ['make', 'test'],
                cwd=str(test_dir),
                env=env,
                check=False  # Don't fail if test fails, let user see output
            )
            
            if test_result.returncode == 0:
                print()
                print("✓ All tests passed!")
            else:
                print()
                print("✗ Tests failed. Check output above for details.", file=sys.stderr)
                sys.exit(1)
                
        except FileNotFoundError:
            print("Warning: make not found. Install make or run tests manually:", file=sys.stderr)
            print(f"  cd pca_compblock_cocotb && make test TOPLEVEL={module_name}", file=sys.stderr)
        except Exception as e:
            print(f"Error running tests: {e}", file=sys.stderr)
            print("You can run tests manually with:", file=sys.stderr)
            print(f"  cd pca_compblock_cocotb && make test TOPLEVEL={module_name}", file=sys.stderr)


if __name__ == '__main__':
    main()

