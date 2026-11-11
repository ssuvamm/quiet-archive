use std::fs;
use std::path::{Path, PathBuf};

use crate::model::{LibraryItem, LibraryStore};

pub struct Library {
    path: PathBuf,
    store: LibraryStore,
}

impl Library {
    pub fn load(path: PathBuf) -> Self {
        let store = fs::read_to_string(&path)
            .ok()
            .and_then(|raw| serde_json::from_str::<LibraryStore>(&raw).ok())
            .unwrap_or_default();
        Self { path, store }
    }

    pub fn all(&self) -> Vec<LibraryItem> {
        let mut items = self.store.items.clone();
        items.sort_by(|a, b| b.saved_at.cmp(&a.saved_at));
        items
    }

    pub fn get(&self, id: &str) -> Option<LibraryItem> {
        self.store.items.iter().find(|i| i.id == id).cloned()
    }

    pub fn total_bytes(&self) -> u64 {
        self.store.items.iter().map(|i| i.size).sum()
    }

    pub fn upsert(&mut self, item: LibraryItem) -> Result<LibraryItem, String> {
        self.store.items.retain(|i| i.id != item.id);
        self.store.items.push(item.clone());
        self.persist()?;
        Ok(item)
    }

    pub fn set_read(&mut self, id: &str, read: bool) -> Result<Option<LibraryItem>, String> {
        let mut found = None;
        for item in self.store.items.iter_mut() {
            if item.id == id {
                item.read = read;
                found = Some(item.clone());
            }
        }
        if found.is_some() {
            self.persist()?;
        }
        Ok(found)
    }

    pub fn remove(&mut self, id: &str) -> Result<Option<LibraryItem>, String> {
        let found = self.get(id);
        if found.is_some() {
            self.store.items.retain(|i| i.id != id);
            self.persist()?;
        }
        Ok(found)
    }

    fn persist(&self) -> Result<(), String> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let json = serde_json::to_string(&self.store).map_err(|e| e.to_string())?;
        let tmp = tmp_path(&self.path);
        fs::write(&tmp, json).map_err(|e| e.to_string())?;
        if fs::rename(&tmp, &self.path).is_err() {
            let _ = fs::remove_file(&self.path);
            fs::rename(&tmp, &self.path).map_err(|e| e.to_string())?;
        }
        Ok(())
    }
}

fn tmp_path(path: &Path) -> PathBuf {
    let mut name = path.file_name().unwrap_or_default().to_os_string();
    name.push(".tmp");
    path.with_file_name(name)
}
