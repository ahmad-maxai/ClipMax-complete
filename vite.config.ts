import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import fs from 'fs';
import path from 'path';
import {defineConfig, type Plugin} from 'vite';

const FORBIDDEN_EXTENSIONS = ['.pdf', '.zip', '.apk'];

function scanForDevEvidence(rootDir: string, label: string): string[] {
  if (!fs.existsSync(rootDir)) return [];
  const findings: string[] = [];

  function walk(currentDir: string) {
    for (const entry of fs.readdirSync(currentDir, {withFileTypes: true})) {
      const fullPath = path.join(currentDir, entry.name);
      const relPath = path.relative(rootDir, fullPath).replace(/\\/g, '/');
      if (relPath === 'assets/aistudio' || relPath.startsWith('assets/aistudio/') || relPath.split('/').includes('aistudio')) {
        findings.push(`${label}/${relPath} (AI Studio development evidence path)`);
        if (entry.isDirectory()) continue;
      }
      if (entry.isDirectory()) {
        walk(fullPath);
      } else {
        const lower = entry.name.toLowerCase();
        if (FORBIDDEN_EXTENSIONS.some((ext) => lower.endsWith(ext))) {
          findings.push(`${label}/${relPath} (forbidden archive/document/APK file)`);
        }
      }
    }
  }

  walk(rootDir);
  return findings;
}

function verifyCleanProductionAssets(): Plugin {
  return {
    name: 'verify-clean-production-assets',
    apply: 'build',
    buildStart() {
      const publicDir = path.resolve(__dirname, 'public');
      const violations = scanForDevEvidence(publicDir, 'public');
      if (violations.length > 0) {
        throw new Error(
          `Production web build aborted: development-only files found in public/:\n - ${violations.join('\n - ')}`
        );
      }
    },
    closeBundle() {
      const distDir = path.resolve(__dirname, 'dist');
      const violations = scanForDevEvidence(distDir, 'dist');
      if (violations.length > 0) {
        throw new Error(
          `Production web build aborted: development-only files found in dist/:\n - ${violations.join('\n - ')}`
        );
      }
    },
  };
}

export default defineConfig(() => {
  return {
    base: './',
    plugins: [react(), tailwindcss(), verifyCleanProductionAssets()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, '.'),
      },
    },
    server: {
      // HMR is disabled in AI Studio via DISABLE_HMR env var.
      // Do not modify—file watching is disabled to prevent flickering during agent edits.
      hmr: process.env.DISABLE_HMR !== 'true',
      // Disable file watching when DISABLE_HMR is true to save CPU during agent edits.
      watch: process.env.DISABLE_HMR === 'true' ? null : {},
    },
  };
});
