//! `vzip convert`'s driver (refkv-cli/src/main.rs's `convert` and `zarrify_beside`), over the
//! options of a C caller instead of a command line. Kept in step with the submodule's revision.
use crate::{Fail, http, output, policy, run, store, transport, vsi};
use std::time::Duration;

#[derive(Debug, Default)]
pub struct Args {
    pub input: String,
    pub out: String,
    pub url: Option<String>,
    pub checksums: bool,
    pub allow_private_hosts: bool,
    pub scope: Option<Vec<String>>,
    pub connections: Option<usize>,
    pub byte_cost: Option<f64>,
    pub workers: usize,
    pub format: output::Container,
}

fn zarrify_beside(a: &Args, client: &std::sync::Arc<http::Client>, beside: refkv_ir::store::StoreFormat) -> Result<(String, serde_json::Value, output::Archive), Fail> {
    let t = transport::Transport::open(&a.input, client)?;
    let scope = a.scope.as_deref().map(|s| vsi::scope_keys(&a.input, s)).transpose()?.flatten();
    let (out, files) = vsi::zarrify_vsi(&a.input, a.url.as_deref(), t, client, a.connections, beside, a.checksums, scope)?;
    let summary = out.summary.clone();
    let (mut archive, objects) = output::store_archive(out, &files.sizes)?;
    if a.checksums {
        let conc = if files.remote() { a.connections.filter(|&c| c > 0).unwrap_or(8) } else { 1 };
        output::checksums(&mut archive, conc, |i, o, n| files.read_range(&objects[i], o, n))?;
    }
    Ok((beside.name().to_string(), summary, archive))
}

pub fn convert(a: &Args) -> Result<serde_json::Value, Fail> {
    if a.url.is_none() && std::path::Path::new(&a.input).is_dir() {
        return Err(Fail::Usage("a local directory needs a source URL (ending in /), the store URL it is served from".into()));
    }
    if let Some(scope) = &a.scope {
        if store::is_store(&a.input) {
            return Err(Fail::Usage("a store input reads the objects its listing gives: scope is for a file input".into()));
        }
        vsi::scope_keys(&a.input, scope)?;
    }
    let policy = policy::Policy { allow_private_hosts: a.allow_private_hosts };
    let client = http::Client::new(policy, Duration::from_secs(60));
    let hub = store::hub_endpoint(std::env::var("HF_ENDPOINT").ok().as_deref())?;
    let (fmt, summary, archive) = if store::is_store(&a.input) {
        let s = store::Store::open(&a.input, a.url.as_deref(), &client, a.workers, &hub)?;
        let (fmt, out) = store::zarrify_store(&s, a.checksums)?;
        let summary = out.summary.clone();
        let (mut archive, objects) = output::store_archive(out, &s.listing().objects)?;
        if a.checksums {
            output::checksums(&mut archive, s.listing().workers, |i, o, n| s.read_range(&objects[i], o, n))?;
        }
        (fmt, summary, archive)
    } else if store::hub_page(&a.input, &hub) {
        return Err(Fail::Rejected(format!("{} is a Hub repository's page, not a file: end it in / to zarrify the repository", a.input)));
    } else if let Some(beside) = run::beside_format(&transport::Transport::open(&a.input, &client)?)? {
        zarrify_beside(a, &client, beside)?
    } else {
        let t = transport::Transport::open(&a.input, &client)?;
        match run::zarrify_file(&a.input, a.url.as_deref(), &t, a.connections, a.byte_cost, a.checksums)? {
            run::Zarrified::Beside(beside) => zarrify_beside(a, &client, beside)?,
            run::Zarrified::File(fmt, out) => {
                let summary = out.summary.clone();
                let etag = if a.url.is_none() || a.url.as_deref() == Some(a.input.as_str()) { t.etag() } else { None };
                let mut archive = output::file_archive(out, t.size(), etag)?;
                if a.checksums {
                    let conc = run::concurrency(&t, a.connections)?;
                    output::checksums(&mut archive, conc, |_, o, n| t.get(o, o + n))?;
                }
                (fmt.name().to_string(), summary, archive)
            }
        }
    };
    output::save(&archive, &a.out, &policy, a.format)?;
    let mut info = serde_json::Map::new();
    info.insert("format".into(), fmt.into());
    if let serde_json::Value::Object(m) = summary {
        for (k, v) in m {
            info.insert(k, v);
        }
    }
    Ok(serde_json::Value::Object(info))
}
