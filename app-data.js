const AppData = {
  PROFILES_KEY: "tt_profiles",
  SCRIPTS_KEY: "tt_scripts",

  // Profiles

  getProfiles() {
    const raw = localStorage.getItem(this.PROFILES_KEY);
    return raw ? JSON.parse(raw) : [];
  },

  saveProfile(profile) {
    const profiles = this.getProfiles();
    if (!profile.id) {
      profile.id = "p_" + Date.now();
      profile.createdAt = new Date().toISOString();
      profiles.unshift(profile);
    } else {
      const idx = profiles.findIndex((p) => p.id === profile.id);
      if (idx !== -1) {
        profiles[idx] = profile;
      }
    }
    localStorage.setItem(this.PROFILES_KEY, JSON.stringify(profiles));
    return profile;
  },

  getProfile(id) {
    const profiles = this.getProfiles();
    const profile = profiles.find((profile) => profile.id === id);

    if (!profile) {
        return null;
    }

    return profile;
  },

  deleteProfile(id) {
    const profiles = this.getProfiles();
    const updatedProfiles = profiles.filter((profile) => profile.id !== id);

    localStorage.setItem(this.PROFILES_KEY,JSON.stringify(updatedProfiles));
  },

  // Saved scripts

  getScripts() {
    const rawScripts = localStorage.getItem(this.SCRIPTS_KEY);

    if (!rawScripts) {
        return [];
    }

    return JSON.parse(rawScripts);
  },

  saveScript(entry) {
    const scripts = this.getScripts();
    entry.id = "s_" + Date.now();
    entry.createdAt = new Date().toISOString();
    scripts.unshift(entry);
    localStorage.setItem(this.SCRIPTS_KEY, JSON.stringify(scripts));
    return entry;
  },

  deleteScript(id) {
    const scripts = this.getScripts();
    const updatedScripts = scripts.filter((script) => script.id !== id);

    localStorage.setItem(this.SCRIPTS_KEY, JSON.stringify(updatedScripts));
  },

  // Helpers

  timeAgo(isoString) {
    const diffMs = Date.now() - new Date(isoString).getTime();
    const mins = Math.floor(diffMs / 60000);
    if (mins < 1) {
        return "just now";
    }
    if (mins < 60) {
        return mins + "m ago";
    }
    const hours = Math.floor(mins / 60);
    if (hours < 24) {
        return hours + "h ago";
    }
    const days = Math.floor(hours / 24);
    return days + "d ago";
  },
};