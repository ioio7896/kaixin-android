//! Usage: dictgen <project-root> <output.dict> [emoji-catalog.tsv]

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if !(3..=4).contains(&args.len()) {
        eprintln!("usage: dictgen <project-root> <output.dict> [emoji-catalog.tsv]");
        std::process::exit(2);
    }
    let emoji_catalog = args.get(3).map(std::path::Path::new);
    match kaixin_engine::compile::build(std::path::Path::new(&args[1]), std::path::Path::new(&args[2]), emoji_catalog) {
        Ok(s) => println!(
            "kaixin.dict: entries={} strings={} alpha={} collocations={} skipped={} bytes={}",
            s.entries, s.words, s.alpha, s.collocations, s.skipped, s.bytes
        ),
        Err(e) => {
            eprintln!("dictgen failed: {e}");
            std::process::exit(1);
        }
    }
}
