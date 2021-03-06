/*
 * Copyright 2016 Ecole des Mines de Saint-Etienne.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.thesmartenergy.sparql.generate.jena;

import com.github.thesmartenergy.sparql.generate.jena.cli.Request;
import com.github.thesmartenergy.sparql.generate.jena.engine.PlanFactory;
import com.github.thesmartenergy.sparql.generate.jena.engine.RootPlan;
import com.github.thesmartenergy.sparql.generate.jena.query.SPARQLGenerateQuery;
import com.github.thesmartenergy.sparql.generate.jena.stream.LocatorFileAccept;
import com.github.thesmartenergy.sparql.generate.jena.stream.LookUpRequest;
import com.github.thesmartenergy.sparql.generate.jena.stream.SPARQLGenerateStreamManager;
import com.github.thesmartenergy.sparql.generate.jena.utils.SPARQLGenerateUtils;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.IOUtils;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.serializer.SerializationContext;
import org.apache.jena.sparql.util.FmtUtils;
import org.apache.log4j.Appender;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Layout;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.apache.log4j.Level;
import org.apache.log4j.PatternLayout;

/**
 *
 * @author Noorani Bakerally <noorani.bakerally at emse.fr>, Maxime Lefrançois
 * <maxime.lefrancois at emse.fr>
 *
 */
public class SPARQLGenerateCli {

    private static final Logger LOG = LoggerFactory.getLogger(SPARQLGenerateCli.class);
    private static final Gson gson = new Gson();

    private static final Layout layout = new PatternLayout("%5p [%t] (%F:%L) - %m%n");
    private static final Appender consoleAppender = new ConsoleAppender(layout);
    private static final org.apache.log4j.Logger rootLogger = org.apache.log4j.Logger.getRootLogger();

    public static void main(String[] args) throws ParseException {

        CommandLine cl = CMDConfigurations.parseArguments(args);

        if (cl.getOptions().length == 0) {
            CMDConfigurations.displayHelp();
            return;
        }

        setLogging(cl);

        String dirPath = cl.getOptionValue("d", ".");
        File dir = new File(dirPath);

        SPARQLGenerate.init();

        // read sparql-generate-conf.json
        Request request;
        try {
            String conf = IOUtils.toString(new FileInputStream(new File(dir, "sparql-generate-conf.json")), "utf-8");
            request = gson.fromJson(conf, Request.class);
        } catch(Exception ex) {
            LOG.warn("Error while loading the location mapping model for the queryset. No named queries will be used");
            request = Request.DEFAULT;
        }
        
        // initialize stream manager
        SPARQLGenerateStreamManager sm = SPARQLGenerateStreamManager.makeStreamManager(new LocatorFileAccept(dir.toURI().getPath()));
        sm.setLocationMapper(request.asLocationMapper());
        SPARQLGenerate.setStreamManager(sm);

        String queryPath = cl.getOptionValue("q", "query.rqg");
        String query;
        try {
            query = IOUtils.toString(SPARQLGenerate.getStreamManager().open(new LookUpRequest(queryPath, SPARQLGenerate.MEDIA_TYPE)), "UTF-8");
        } catch (IOException | NullPointerException ex) {
            LOG.error("No file named " + queryPath + " was found in the directory that contains the query to be executed.");
            return;
        }

        SPARQLGenerateQuery q;
        try {
            q = (SPARQLGenerateQuery) QueryFactory.create(query, SPARQLGenerate.SYNTAX);
        } catch (Exception ex) {
            LOG.error("Error while parsing the query to be executed.", ex);
            return;
        }

        RootPlan plan;
        try {
            plan = PlanFactory.create(q);
        } catch (Exception ex) {
            LOG.error("Error while creating the plan for the query.", ex);
            return;
        }

        Dataset ds;
        try {
            ds = SPARQLGenerateUtils.loadDataset(dir, request);
        } catch (Exception ex) {
            LOG.warn("Error while loading the dataset, no dataset will be used.");
            ds = DatasetFactory.create();
        }

        String output = cl.getOptionValue("o");

        boolean stream = cl.hasOption("s");
        if (stream) {

            StreamRDF outputRDF;
            if (output == null) {
                outputRDF = new ConsoleStreamRDF(System.out, q.getPrefixMapping());
            } else {
                try {
                    outputRDF = new ConsoleStreamRDF(new PrintStream(new FileOutputStream(output, false)), q.getPrefixMapping());
                } catch (IOException ex) {
                    LOG.error("Error while opening the output file.", ex);
                    return;
                }
            }
            try {
                plan.exec(ds, outputRDF);
            } catch (Exception ex) {
                LOG.error("Error while executing the plan.", ex);
            }

        } else {

            try {
                Model model = plan.exec(ds);
                if (output == null) {
                    model.write(System.out, "TTL");
                } else {
                    try {
                        model.write(new FileOutputStream(output, false), "TTL");
                    } catch (IOException ex) {
                        LOG.error("Error while opening the output file.", ex);
                        return;
                    }
                }
            } catch (Exception ex) {
                LOG.error("Error while executing the plan.", ex);
            }

        }
    }

    private static class ConsoleStreamRDF implements StreamRDF {

        private final PrefixMapping pm;
        private final SerializationContext context;

        private PrintStream out;
        
        int  i = 0;

        public ConsoleStreamRDF(PrintStream out, PrefixMapping pm) {
            this.out = out;
            this.pm = pm;
            context = new SerializationContext(pm);
        }

        @Override
        public void start() {
            pm.getNsPrefixMap().forEach((prefix, uri) -> {
                out.append("@prefix ").append(prefix).append(": <").append(uri).append("> .\n");
            });
        }

        @Override
        public void base(String string) {
            out.append("@base <").append(string).append(">\n");
        }

        @Override
        public void prefix(String prefix, String uri) {
            out.append("@prefix ").append(prefix).append(": <").append(uri).append("> .\n");
        }

        @Override
        public void triple(Triple triple) {
            out.append(FmtUtils.stringForTriple(triple, context)).append(" .\n");
            i++;
            if(i>1000) {
                i=0;
                out.flush();
            }
        }

        @Override
        public void quad(Quad quad) {
        }

        @Override
        public void finish() {
            LOG.info("end of transformation");
        }
    }

    private static void setLogging(CommandLine cl) {
        try {
            Level level = Level.toLevel(cl.getOptionValue("l"), Level.DEBUG);
            rootLogger.setLevel(level);
        } catch (Exception ex) {
            rootLogger.setLevel(Level.DEBUG);
        }

        String logPath = cl.getOptionValue("f");
        if (logPath == null) {
            rootLogger.addAppender(consoleAppender);
        } else {
            try {
                rootLogger.addAppender(new org.apache.log4j.RollingFileAppender(layout, logPath, false));
            } catch (IOException ex) {
                System.out.println(ex.getClass() + "occurred while initializing the log file: " + ex.getMessage());
                ex.printStackTrace();
                return;
            }
        }
    }

    private static class CMDConfigurations {

        public static CommandLine parseArguments(String[] args) throws ParseException {

            DefaultParser commandLineParser = new DefaultParser();
            CommandLine cl = commandLineParser.parse(getCMDOptions(), args);

            /*Process Options*/
            //print help menu
            if (cl.hasOption('h')) {
                CMDConfigurations.displayHelp();
            }

            return cl;
        }

        public static Options getCMDOptions() {
            Options opt = new Options()
                    .addOption("h", "help", false, "Show help")
                    .addOption("d", "dir", true, "Location of the directory with the queryset, documentset, dataset, and configuration files as explained in https://w3id.org/sparql-generate/language-cli.html. Default value is . (the current folder)")
                    .addOption("q", "query-file", true, "Name of the query file in the directory. Default value is ./query.rqg")
                    .addOption("o", "output", true, "Location where the output is to be stored. No value means output goes to the console.")
                    .addOption("l", "log-level", true, "Set log level, acceptable values are TRACE < DEBUG < INFO < WARN < ERROR < FATAL < OFF. No value or unrecognized value results in level DEBUG")
                    .addOption("f", "log-file", true, "Location where the log is to be stored. No value means output goes to the console.")
                    .addOption("s", "stream", false, "Generate output as stream.");
            return opt;
        }

        public static void displayHelp() {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("SPARQL-Generate processor", getCMDOptions());
            System.exit(1);
        }
    }

}
