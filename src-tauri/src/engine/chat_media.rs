use anyhow::{bail, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use std::{io::Read, path::Path};
pub fn mime(bytes: &[u8]) -> Option<&'static str> {
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some("image/png")
    } else if bytes.starts_with(&[0xff, 0xd8, 0xff]) {
        Some("image/jpeg")
    } else if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") {
        Some("image/gif")
    } else if bytes.len() >= 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        Some("image/webp")
    } else if bytes.starts_with(b"BM") {
        Some("image/bmp")
    } else {
        None
    }
}
pub fn read_image(path: &Path) -> Result<Option<String>> {
    let file = std::fs::File::open(path)?;
    const LIMIT: u64 = 10 * 1024 * 1024;
    if file.metadata()?.len() > LIMIT {
        bail!("图片超过 10 MB 预览上限，原文件不受影响");
    }
    let mut bytes = Vec::new();
    file.take(LIMIT + 1).read_to_end(&mut bytes)?;
    if bytes.len() as u64 > LIMIT {
        bail!("图片超过预览上限");
    }
    Ok(mime(&bytes).map(|mime| format!("data:{mime};base64,{}", STANDARD.encode(bytes))))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn signatures_reject_text_and_svg() {
        assert_eq!(mime(b"<svg/>"), None);
        assert_eq!(mime(b"fake.png"), None);
        assert_eq!(mime(b"GIF89a"), Some("image/gif"));
        assert_eq!(mime(b"\xff\xd8\xff"), Some("image/jpeg"));
    }
    #[test]
    fn reads_bounded_file_and_encodes() {
        let p = std::env::temp_dir().join(format!("ztdrop-image-{}.png", rand::random::<u64>()));
        std::fs::write(&p, b"\x89PNG\r\n\x1a\n").unwrap();
        assert_eq!(
            read_image(&p).unwrap().unwrap(),
            "data:image/png;base64,iVBORw0KGgo="
        );
        let f = std::fs::File::create(&p).unwrap();
        f.set_len(10 * 1024 * 1024 + 1).unwrap();
        assert!(read_image(&p).is_err());
        std::fs::remove_file(p).unwrap();
    }
}
