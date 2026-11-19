// Quarantines every plugin jar on a Pterodactyl server and reinstalls clean copies from official sources.
// Usage: node reinstall-plugins.mjs            (dry run, changes nothing)
//        node reinstall-plugins.mjs --apply    (does it)
// One server:   PANEL_URL, API_KEY, SERVER_ID, GAME_VERSION (default 1.21.11)
// List servers: node reinstall-plugins.mjs --list          (shows every server the key can see: id + name)
// Many servers: node reinstall-plugins.mjs --config servers.json [--apply]
//   servers.json = [{"name":"WILDKITS","panel":"https://panel.primehost.cc","keyEnv":"KEY_PRIME","id":"884c1c8f","game":"1.21.11"}, ...]
//   keyEnv is the NAME of an environment variable that holds that panel's API key (keys never go in the file).
// Only plugins that were really in that server's /plugins folder are reinstalled.
import { readFileSync } from "node:fs";

const argv = process.argv.slice(2);
const flag = (n) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : null; };
const APPLY = argv.includes("--apply");
let PANEL = (process.env.PANEL_URL || "https://panel.primehost.cc").replace(/\/+$/, "");
let KEY = process.env.API_KEY;
let SID = process.env.SERVER_ID || "884c1c8f";
let GAME = process.env.GAME_VERSION || "1.21.11";
const UA = { "User-Agent": "stark-plugin-reinstall/1.0 (OajajI)" };

const PLUGINS = [
  { name: "ViaVersion", modrinth: "viaversion" },
  { name: "ViaBackwards", modrinth: "viabackwards" },
  { name: "LuckPerms", modrinth: "luckperms" },
  { name: "Vault", github: "MilkBowl/Vault", asset: /^Vault\.jar$/i },
  { name: "floodgate", url: "https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/spigot", file: "floodgate-spigot.jar" },
  { name: "Multiverse-Core", modrinth: "multiverse-core" },
  { name: "TAB", search: "TAB" },
  { name: "GrimAC", modrinth: "grimac" },
  { name: "PlaceholderAPI", modrinth: "placeholderapi" },
  { name: "FastAsyncWorldEdit", modrinth: "fastasyncworldedit" },
  { name: "WorldGuard", search: "WorldGuard" },
  { name: "ProtocolLib", github: "dmulloy2/ProtocolLib", asset: /^ProtocolLib\.jar$/i },
  { name: "Skript", github: "SkriptLang/Skript", asset: /^Skript-[\d.]+\.jar$/i },
  { name: "Votifier", alias: ["nuvotifier"], github: "NuVotifier/NuVotifier", asset: /universal.*\.jar$/i },
  { name: "VotingPlugin", search: "VotingPlugin" },
  { name: "FancyHolograms", modrinth: "fancyholograms" },
  { name: "FancyNpcs", modrinth: "fancynpcs" },
  { name: "PlugManX", search: "PlugManX" },
  { name: "ajLeaderboards", modrinth: "ajleaderboards" },
  { name: "AxShulkers", search: "AxShulkers" },
  { name: "Shopkeepers", search: "Shopkeepers" },
  { name: "DeluxeMenus", search: "DeluxeMenus" },
  { name: "InventoryRollbackPlus", search: "InventoryRollbackPlus" },
  { name: "BetterTeams", search: "BetterTeams" },
  { name: "AxiomPaper", search: "Axiom" },
  { name: "PvPManager", search: "PvPManager" },
];
const MANUAL = ["AreaReset", "AttributeSwapFixer", "AutoTNT", "ChatFilter", "HidePlugins", "ISpawn", "ItemEdit", "KnockBalls", "NightVision",
  "PhoenixCratesLite", "PlayerMap", "RandomKit", "RodPull", "Stark-essential", "StarkAFK", "StarkChat", "StarkFly", "StarkMSG", "StarkModeration",
  "StarkNametag", "StarkSay", "StarkSayAll", "StarkWGTab", "SupplyDrop", "WorldGuardExtraFlagsPlus", "totemghostfix"];


const api = (path, opts = {}) => fetch(`${PANEL}/api/client/servers/${SID}${path}`, {
  ...opts,
  headers: { Authorization: `Bearer ${KEY}`, Accept: "Application/vnd.pterodactyl.v1+json", "Content-Type": "application/json", ...(opts.headers || {}) },
});
async function must(res, what) {
  if (!res.ok) throw new Error(`${what} failed: HTTP ${res.status} ${(await res.text()).slice(0, 200)}`);
  return res;
}
const json = (url) => fetch(url, { headers: UA }).then((r) => (r.ok ? r.json() : null));

async function modrinthFile(slug) {
  for (const loaders of [["paper"], ["spigot"], ["bukkit"]]) {
    const q = `loaders=${encodeURIComponent(JSON.stringify(loaders))}&game_versions=${encodeURIComponent(JSON.stringify([GAME]))}`;
    const versions = await json(`https://api.modrinth.com/v2/project/${slug}/version?${q}`);
    if (!versions || !versions.length) continue;
    const v = versions.find((x) => x.version_type === "release") || versions[0];
    const f = v.files.find((x) => x.primary) || v.files[0];
    return { url: f.url, file: f.filename, sha512: f.hashes?.sha512, from: `modrinth:${slug} ${v.version_number}` };
  }
  return null;
}
async function modrinthSearch(name) {
  const facets = encodeURIComponent('[["project_type:plugin"]]');
  const res = await json(`https://api.modrinth.com/v2/search?query=${encodeURIComponent(name)}&facets=${facets}&limit=8`);
  const norm = (s) => s.toLowerCase().replace(/[^a-z0-9]/g, "");
  const hit = res?.hits?.find((h) => norm(h.title) === norm(name) || norm(h.slug) === norm(name));
  return hit ? modrinthFile(hit.slug) : null;
}
async function githubFile(repo, asset) {
  const rel = await json(`https://api.github.com/repos/${repo}/releases/latest`);
  const a = rel?.assets?.find((x) => asset.test(x.name));
  return a ? { url: a.browser_download_url, file: a.name, from: `github:${repo} ${rel.tag_name}` } : null;
}
async function resolve(p) {
  if (p.url) return { url: p.url, file: p.file, from: "direct" };
  if (p.modrinth) return modrinthFile(p.modrinth);
  if (p.github) return githubFile(p.github, p.asset);
  if (p.search) return modrinthSearch(p.search);
  return null;
}

async function main(label) {
  console.log(`\n===== ${label || SID} =====`);
  console.log(APPLY ? "MODE: APPLY" : "MODE: DRY RUN (add --apply to really do it)");
  const st = await (await must(await api("/resources"), "status")).json();
  const state = st.attributes.current_state;
  console.log("Server state:", state);
  if (state !== "offline" && APPLY) { console.error("Stop the server first."); process.exit(1); }

  const listing = await (await must(await api("/files/list?directory=%2Fplugins"), "list plugins")).json();
  const names = listing.data.map((e) => e.attributes);
  const jars = names.filter((e) => e.is_file && e.name.toLowerCase().endsWith(".jar")).map((e) => e.name);
  const wanted = PLUGINS.filter((p) => jars.some((j) => [p.name, ...(p.alias || [])].some((n) =>
    new RegExp("^" + n.toLowerCase().replace(/[^a-z0-9]/g, ".") + "(?![a-z])").test(j.toLowerCase()))));
  const unknown = jars.filter((j) => !wanted.some((p) => [p.name, ...(p.alias || [])].some((n) =>
    new RegExp("^" + n.toLowerCase().replace(/[^a-z0-9]/g, ".") + "(?![a-z])").test(j.toLowerCase()))));
  const remapped = names.some((e) => !e.is_file && e.name === ".paper-remapped");
  console.log(`Found ${jars.length} jars${remapped ? " + .paper-remapped" : ""} in /plugins`);

  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  const qdir = `_quarantine/${stamp}`;
  if (APPLY) {
    await must(await api("/files/create-folder", { method: "POST", body: JSON.stringify({ root: "/", name: "_quarantine" }) }), "create _quarantine").catch(() => {});
    await must(await api("/files/create-folder", { method: "POST", body: JSON.stringify({ root: "/_quarantine", name: stamp }) }), "create folder");
    const files = jars.map((n) => ({ from: `plugins/${n}`, to: `${qdir}/${n}` }));
    if (remapped) files.push({ from: "plugins/.paper-remapped", to: `${qdir}/.paper-remapped` });
    if (files.length) await must(await api("/files/rename", { method: "PUT", body: JSON.stringify({ root: "/", files }) }), "move to quarantine");
    console.log(`Moved to /${qdir}`);
  } else {
    console.log(`Would move ${jars.length} jars to /${qdir}`);
  }

  let uploadUrl = null;
  if (APPLY) uploadUrl = (await (await must(await api("/files/upload"), "upload url")).json()).attributes.url;

  const ok = [], failed = [];
  for (const p of wanted) {
    try {
      const f = await resolve(p);
      if (!f) { failed.push(`${p.name}: no ${GAME} build found automatically`); continue; }
      if (!APPLY) { ok.push(`${p.name}  <-  ${f.from}  (${f.file})`); continue; }
      const res = await fetch(f.url, { headers: UA, redirect: "follow" });
      if (!res.ok) throw new Error(`download HTTP ${res.status}`);
      const buf = Buffer.from(await res.arrayBuffer());
      if (f.sha512) {
        const { createHash } = await import("node:crypto");
        if (createHash("sha512").update(buf).digest("hex") !== f.sha512) throw new Error("hash mismatch, skipped");
      }
      if (buf.includes("l/M/x/")) throw new Error("contains the known malicious class, skipped");
      if (buf.subarray(0, 2).toString() !== "PK") throw new Error("not a jar file");
      const fd = new FormData();
      fd.append("files", new Blob([buf]), f.file);
      await must(await fetch(`${uploadUrl}&directory=${encodeURIComponent("/plugins")}`, { method: "POST", body: fd }), "upload");
      ok.push(`${p.name}  <-  ${f.from}  (${f.file})`);
    } catch (e) {
      failed.push(`${p.name}: ${e.message}`);
    }
  }
  console.log(`\nInstalled${APPLY ? "" : " (preview)"}: ${ok.length}`);
  ok.forEach((l) => console.log("  +", l));
  console.log(`\nNeed manual download / not found: ${failed.length + unknown.length}`);
  failed.forEach((l) => console.log("  !", l));
  unknown.forEach((n) => console.log("  !", n, "(manual: official site, or rebuild from your own source)"));
  console.log("\nOld jars are kept in the quarantine folder. Do NOT put them back.");
}
async function listServers() {
  const res = await must(await fetch(`${PANEL}/api/client`, { headers: { Authorization: `Bearer ${KEY}`, Accept: "Application/vnd.pterodactyl.v1+json" } }), "list servers");
  for (const s of (await res.json()).data) console.log(s.attributes.identifier, "  ", s.attributes.name);
}

(async () => {
  const cfg = flag("--config");
  if (cfg) {
    for (const e of JSON.parse(readFileSync(cfg, "utf8"))) {
      PANEL = e.panel.replace(/\/+$/, ""); KEY = process.env[e.keyEnv]; SID = e.id; GAME = e.game || "1.21.11";
      if (!KEY) { console.error(`Skipping ${e.name}: environment variable ${e.keyEnv} is not set`); continue; }
      await main(e.name).catch((err) => console.error(`${e.name}: ${err.message}`));
    }
    return;
  }
  if (!KEY) { console.error("Set API_KEY first (Panel > Account > API Credentials)."); process.exit(1); }
  if (argv.includes("--list")) return listServers();
  await main();
})().catch((e) => { console.error(e.message); process.exit(1); });
