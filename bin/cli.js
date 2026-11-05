#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const PAYLOAD = path.join(ROOT, 'payload');
const VERSION = fs.readFileSync(path.join(ROOT, 'VERSION'), 'utf8').trim();
const PRUNE = [path.join('Keywords', 'runclock'), path.join('Include', 'config', 'runclock')];
const SETTINGS = path.join('Include', 'config', 'runclock', 'runclock.properties');

function payloadFiles(dir = PAYLOAD) {
    return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
        const full = path.join(dir, entry.name);
        return entry.isDirectory() ? payloadFiles(full) : [path.relative(PAYLOAD, full)];
    });
}

function projectRoot(rawPath) {
    if (!rawPath || !fs.existsSync(rawPath)) {
        throw new Error(`Project folder not found: ${rawPath || '(none given)'}`);
    }
    const root = fs.realpathSync(rawPath);
    if (!fs.readdirSync(root).some((name) => name.toLowerCase().endsWith('.prj'))) {
        throw new Error(`No .prj file in ${root}. Point to the Katalon project folder.`);
    }
    return root;
}

function install(rawPath) {
    const root = projectRoot(rawPath);
    console.log(`Run Clock ${VERSION} -> ${root}`);
    for (const rel of payloadFiles()) {
        const target = path.join(root, rel);
        if (rel === SETTINGS && fs.existsSync(target)) {
            console.log(`  kept     ${rel}`);
            continue;
        }
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.copyFileSync(path.join(PAYLOAD, rel), target);
        console.log(`  added    ${rel}`);
    }
    console.log('\nIn Katalon Studio: right-click Keywords, Test Listeners and Include > Refresh, then run any test suite.');
    console.log('After a run from Katalon Studio its report opens in your browser. Every run: Reports/run-clock/index.html');
}

function uninstall(rawPath) {
    const root = projectRoot(rawPath);
    console.log(`Removing Run Clock from ${root}`);
    for (const rel of payloadFiles()) {
        const target = path.join(root, rel);
        if (rel === SETTINGS) {
            continue;
        }
        if (fs.existsSync(target)) {
            fs.unlinkSync(target);
            console.log(`  removed  ${rel}`);
        }
    }
    for (const rel of PRUNE) {
        const dir = path.join(root, rel);
        if (fs.existsSync(dir) && fs.readdirSync(dir).length === 0) {
            fs.rmdirSync(dir);
        }
    }
    console.log('\nRecorded timings in Reports/ and your settings in ' + SETTINGS.split(path.sep).join('/') + ' were kept.');
}

function main([command, projectPath]) {
    if (command === 'install') {
        return install(projectPath);
    }
    if (command === 'uninstall') {
        return uninstall(projectPath);
    }
    if (command === '--version' || command === '-v') {
        return console.log(VERSION);
    }
    console.log(`katalon-run-clock ${VERSION}

Usage:
  katalon-run-clock install <katalon-project-folder>
  katalon-run-clock uninstall <katalon-project-folder>`);
    process.exitCode = command ? 1 : 0;
}

try {
    main(process.argv.slice(2));
} catch (error) {
    console.error(`Error: ${error.message}`);
    process.exitCode = 1;
}
