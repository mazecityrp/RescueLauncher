/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher;

import com.skcraft.concurrency.ProgressObservable;
import com.skcraft.launcher.model.modpack.ManifestInfo;
import com.skcraft.launcher.model.modpack.PackageList;
import com.skcraft.launcher.persistence.Persistence;
import com.skcraft.launcher.util.HttpRequest;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.java.Log;
import org.apache.commons.io.filefilter.DirectoryFileFilter;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import static com.skcraft.launcher.LauncherUtils.concat;
import static com.skcraft.launcher.util.SharedLocale._;

@Log
public class InstanceList {

    private static int PARSER_VERSION = 1;
    private final Launcher launcher;
    @Getter private final List<Instance> instances = new ArrayList<Instance>();

    public InstanceList(@NonNull Launcher launcher) {
        this.launcher = launcher;
    }

    public synchronized Instance get(int index) {
        return instances.get(index);
    }

    public synchronized int size() {
        return instances.size();
    }

    public Enumerator createEnumerator() {
        return new Enumerator();
    }

    public synchronized List<Instance> getSelected() {
        List<Instance> selected = new ArrayList<Instance>();
        for (Instance instance : instances) {
            if (instance.isSelected()) {
                selected.add(instance);
            }
        }

        return selected;
    }

    public synchronized void sort() {
        Collections.sort(instances);
    }

    public final class Enumerator implements Callable<InstanceList>, ProgressObservable {
        private Enumerator() {
        }

        @Override
        public InstanceList call() throws Exception {
            log.info("Enumerating instance list...");

            List<Instance> local = new ArrayList<Instance>();
            List<Instance> remote = new ArrayList<Instance>();

            File[] dirs = launcher.getInstancesDir().listFiles((FileFilter) DirectoryFileFilter.INSTANCE);
            if (dirs != null) {
                for (File dir : dirs) {
                    File file = new File(dir, "instance.json");
                    Instance instance = Persistence.load(file, Instance.class);
                    instance.setDir(dir);
                    instance.setName(dir.getName());
                    instance.setSelected(true);
                    instance.setLocal(true);
                    local.add(instance);

                    log.info(instance.getName() + " local instance found at " + dir.getAbsolutePath());
                }
            }

            try {
                URL packagesURL = launcher.getPackagesURL();

                PackageList packages = HttpRequest
                        .get(packagesURL)
                        .execute()
                        .expectResponseCode(200)
                        .returnContent()
                        .asJson(PackageList.class);

                if (packages.getMinimumVersion() > PARSER_VERSION) {
                    throw new LauncherException("Update required", _("errors.updateRequiredError"));
                }

                for (ManifestInfo manifest : packages.getPackages()) {
                    boolean foundLocal = false;

                    for (Instance instance : local) {
                        if (instance.getName().equalsIgnoreCase(manifest.getName())) {
                            foundLocal = true;

                            instance.setTitle(manifest.getTitle());
                            instance.setPriority(manifest.getPriority());
                            URL url = concat(packagesURL, manifest.getLocation());
                            instance.setManifestURL(url);

                            log.info("(" + instance.getName() + ").setManifestURL(" + url + ")");

                            // Check if an update is required
                            if (instance.getVersion() == null || !instance.getVersion().equals(manifest.getVersion())) {
                                instance.setUpdatePending(true);
                                instance.setVersion(manifest.getVersion());
                                Persistence.commitAndForget(instance);
                                log.info(instance.getName() + " requires an update to " + manifest.getVersion());
                            }
                        }
                    }

                    if (!foundLocal) {
                        File dir = new File(launcher.getInstancesDir(), manifest.getName());
                        File file = new File(dir, "instance.json");
                        Instance instance = Persistence.load(file, Instance.class);
                        instance.setDir(dir);
                        instance.setTitle(manifest.getTitle());
                        instance.setName(manifest.getName());
                        instance.setVersion(manifest.getVersion());
                        instance.setPriority(manifest.getPriority());
                        instance.setSelected(false);
                        instance.setManifestURL(concat(packagesURL, manifest.getLocation()));
                        instance.setUpdatePending(true);
                        instance.setLocal(false);
                        remote.add(instance);

                        log.info("Available remote instance: '" + instance.getName() +
                                "' at version " + instance.getVersion());
                    }
                }
            } catch (IOException e) {
                throw new IOException("The list of modpacks could not be downloaded.", e);
            } finally {
                synchronized (InstanceList.this) {
                    instances.clear();
                    instances.addAll(local);
                    instances.addAll(remote);

                    log.info(instances.size() + " instance(s) enumerated.");
                }
            }

            return InstanceList.this;
        }

        @Override
        public double getProgress() {
            return -1;
        }
    }
}
