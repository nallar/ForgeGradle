/*
 * A Gradle plugin for the creation of Minecraft mods and MinecraftForge plugins.
 * Copyright (C) 2013 Minecraft Forge
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package net.minecraftforge.gradle.tasks;

import groovy.lang.Closure;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import net.minecraftforge.gradle.common.Constants;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.FileCollection;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFiles;
import org.gradle.api.tasks.TaskAction;

import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import com.google.common.io.LineProcessor;

public class CrowdinDownload extends DefaultTask
{
    @Input
    private Object              projectId;
    @Input
    private Object              apiKey;
    @Input
    private boolean             extract      = true;
    private Object              output;

    // format these with the projectId and apiKey
    private static final String EXPORT_URL   = "https://api.crowdin.com/api/project/%s/export?key=%s";
    private static final String DOWNLOAD_URL = "https://api.crowdin.com/api/project/%s/download/all.zip?key=%s";

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public CrowdinDownload()
    {
        super();

        this.onlyIf(new Spec() {

            @Override
            public boolean isSatisfiedBy(Object arg0)
            {
                CrowdinDownload task = (CrowdinDownload) arg0;

                // no API key? skip
                if (Strings.isNullOrEmpty(task.getApiKey()))
                {
                    getLogger().lifecycle("Crowdin api key is null, skipping task.");
                    return false;
                }

                // offline? skip.
                if (getProject().getGradle().getStartParameter().isOffline())
                {
                    getLogger().lifecycle("Gradle is in offline mode, skipping task.");
                    return false;
                }

                return true;
            }

        });
    }

    @TaskAction
    public void doTask() throws IOException
    {
        String project = getProjectId();
        String key = getApiKey();

        exportLocalizations(project, key);
        getLocalizations(project, key, getOutput());
    }

    private void exportLocalizations(String projectId, String key) throws IOException
    {
        getLogger().debug("Exporting crowdin localizations.");
        URL url = new URL(String.format(EXPORT_URL, projectId, key));

        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestProperty("User-Agent", Constants.USER_AGENT);
        con.setInstanceFollowRedirects(true);

        try
        {
            con.connect();
        }
        catch (Throwable e)
        {
            // just in case people dont have internet at the moment.
            Throwables.propagate(e);
        }

        int reponse = con.getResponseCode();
        con.disconnect();

        if (reponse == 401)
            throw new RuntimeException("Invalid Crowdin API-Key!");
    }

    private void getLocalizations(String projectId, String key, File output) throws IOException
    {
        getLogger().info("Downloading crowdin localizations.");
        URL url = new URL(String.format(DOWNLOAD_URL, projectId, key));

        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestProperty("User-Agent", Constants.USER_AGENT);
        con.setInstanceFollowRedirects(true);

        ZipOutputStream zOut = null;
        try (ZipInputStream zStream = new ZipInputStream(con.getInputStream()))
        {
            if (!extract)
            {
                Files.createParentDirs(output);
                Files.touch(output);
                zOut = new ZipOutputStream(new FileOutputStream(output));
            }

            ZipEntry entry;
            while ((entry = zStream.getNextEntry()) != null)
            {
                try
                {
                    if (entry.isDirectory() || entry.getSize() == 0)
                    {
                        continue;
                    }

                    String data = CharStreams.readLines(new InputStreamReader(zStream), new LineProcessor<String>()
                    {
                        StringBuilder out = new StringBuilder();
                        Splitter SPLITTER = Splitter.on('=').limit(2);

                        @Override
                        public boolean processLine(String line) throws IOException
                        {
                            String[] pts = Iterables.toArray(SPLITTER.split(line), String.class);
                            if (pts.length == 2)
                            {
                                out.append(pts[0]).append('=').append(
                                        pts[1].replace("\\!", "!")
                                                .replace("\\:", ":"))
                                        .append('\n');
                            } else
                                out.append(line).append('\n');
                            return true;
                        }

                        @Override
                        public String getResult()
                        {
                            return out.toString();
                        }
                    });

                    if (extract)
                    {
                        getLogger().debug("Extracting file: " + entry.getName());
                        File out = new File(output, entry.getName());
                        Files.createParentDirs(out);
                        Files.touch(out);
                        Files.write(data.getBytes(Charsets.UTF_8), out);
                    } else
                    {
                        try
                        {
                            zOut.putNextEntry(new ZipEntry(entry.getName()));
                            zOut.write(data.getBytes(Charsets.UTF_8));
                        } finally
                        {
                            zOut.closeEntry();
                        }
                    }
                } finally
                {
                    zStream.closeEntry();
                }
            }
        } finally
        {
            if (zOut != null)
                zOut.close();
        }

        con.disconnect();
    }

    @SuppressWarnings("rawtypes")
    public String getProjectId()
    {
        if (projectId == null)
            throw new NullPointerException("ProjectID must be set for crowdin!");

        while (projectId instanceof Closure)
            projectId = ((Closure) projectId).call();

        return projectId.toString();
    }

    public void setProjectId(Object projectId)
    {
        this.projectId = projectId;
    }

    @SuppressWarnings("rawtypes")
    public String getApiKey()
    {
        while (apiKey instanceof Closure)
            apiKey = ((Closure) apiKey).call();

        if (apiKey == null)
            return null;

        return apiKey.toString();
    }

    public void setApiKey(Object apiKey)
    {
        this.apiKey = apiKey;
    }

    @OutputFiles
    public FileCollection getOutputFiles()
    {
        if (isExtract())
            return getProject().fileTree(getOutput());
        else
            return getProject().files(getOutput());
    }

    public File getOutput()
    {
        return getProject().file(output);
    }

    public void setOutput(Object output)
    {
        this.output = output;
    }

    public boolean isExtract()
    {
        return extract;
    }

    public void setExtract(boolean extract)
    {
        this.extract = extract;
    }
}
