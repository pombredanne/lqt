/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.basistech.lucene.tools;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.io.LineIterator;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.tools.ant.types.Commandline;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <code>LuceneQueryTool</code> is a command line tool for executing Lucene
 * queries and formatting the results.  The usage summary is shown below.
 * Please refer to README.md for complete documentation.
 *
 * <pre>
 * usage: LuceneQueryTool [options]
 *     --analyzer &lt;arg&gt;       for query, (KeywordAnalyzer | StandardAnalyzer)
 *                            (defaults to KeywordAnalyzer)
 *     --fields &lt;arg&gt;         fields to include in output (defaults to all)
 *     --format &lt;arg&gt;         output format (multiline | tabular | json |
 *                            json-pretty) (defaults to multiline)
 *  -i,--index &lt;arg&gt;          index (required, multiple -i searches multiple
 *                            indexes)
 *  -o,--output &lt;arg&gt;         output file (defaults to standard output)
 *     --output-limit &lt;arg&gt;   max number of docs to output
 *  -q,--query &lt;arg&gt;          (query | %all | %enumerate-fields |
 *                            %count-fields | %enumerate-terms field |
 *                            %script scriptFile | %ids id [id ...] |
 *                            %id-file file) (required, scriptFile may
 *                            contain -q and -o)
 *     --query-field &lt;arg&gt;    default field for query
 *     --query-limit &lt;arg&gt;    same as --output-limit
 *     --regex &lt;arg&gt;          filter query by regex, syntax is field:/regex/
 *     --show-hits            show total hit count
 *     --show-id              show Lucene document id in results
 *     --show-score           show score in results
 *     --sort-fields          sort fields within document
 *     --suppress-names       suppress printing of field names
 *     --tabular              print tabular output (requires --fields)
 * </pre>
 */
public final class LuceneQueryTool {
    private List<String> fieldNames;
    private Set<String> allFieldNames;
    private int outputLimit;
    private String regexField;
    private Pattern regex;
    private boolean showId;
    private boolean showHits;
    private boolean showScore;
    private boolean sortFields;
    private Analyzer analyzer;
    private String defaultField;
    private IndexReader indexReader;
    private PrintStream defaultOut;
    private int docsPrinted;
    private Formatter formatter;

    LuceneQueryTool(IndexReader reader, PrintStream out) throws IOException {
        this.indexReader = reader;
        this.outputLimit = Integer.MAX_VALUE;
        this.analyzer = new KeywordAnalyzer();
        this.fieldNames = Lists.newArrayList();
        this.defaultOut = out;
        allFieldNames = Sets.newTreeSet();
        for (LeafReaderContext leaf : reader.leaves()) {
            for (FieldInfo fieldInfo : leaf.reader().getFieldInfos()) {
                allFieldNames.add(fieldInfo.name);
            }
        }
        this.formatter = Formatter.newInstance(Formatter.Format.MULTILINE, false);
    }

    LuceneQueryTool(IndexReader reader) throws IOException {
        this(reader, System.out);
    }

    void setFieldNames(List<String> fieldNames) {
        List<String> invalidFieldNames = Lists.newArrayList();
        for (String field : fieldNames) {
            if (!allFieldNames.contains(field)) {
                invalidFieldNames.add(field);
            }
        }
        if (!invalidFieldNames.isEmpty()) {
            throw new RuntimeException("Invalid field names: " + invalidFieldNames);
        }
        this.fieldNames.addAll(fieldNames);
    }

    void setAnalyzer(String analyzerString) {
        if ("KeywordAnalyzer".equals(analyzerString)) {
            this.analyzer = new KeywordAnalyzer();
        } else if ("StandardAnalyzer".equals(analyzerString)) {
            this.analyzer = new StandardAnalyzer();
        } else {
            throw new RuntimeException(
                    String.format("Invalid analyzer %s: %s",
                            analyzerString,
                            "Only KeywordAnalyzer and StandardAnalyzer currently supported"));
        }
    }

    // same as outputLimit; for compatibility
    void setQueryLimit(int queryLimit) {
        this.outputLimit = queryLimit;
    }

    void setOutputLimit(int outputLimit) {
        this.outputLimit = outputLimit;
    }

    void setOutputStream(PrintStream out) {
        this.defaultOut = out;
    }

    void setRegex(String regexField, Pattern regex) {
        if (!allFieldNames.contains(regexField)) {
            throw new RuntimeException("Invalid field name: " + regexField);
        }
        if (!fieldNames.isEmpty() && !fieldNames.contains(regexField)) {
            throw new RuntimeException("Attempted to apply regex to field not in results: " + regexField);
        }
        this.regexField = regexField;
        this.regex = regex;
    }

    void setShowId(boolean showId) {
        this.showId = showId;
    }

    void setSortFields(boolean sortFields) {
        this.sortFields = sortFields;
    }

    void setShowHits(boolean showHits) {
        this.showHits = showHits;
    }

    void setShowScore(boolean showScore) {
        this.showScore = showScore;
    }

    void setDefaultField(String defaultField) {
        if (!allFieldNames.contains(defaultField)) {
            throw new RuntimeException("Invalid field name: " + defaultField);
        }
        this.defaultField = defaultField;
    }

    void setFormatter(Formatter formatter) {
        this.formatter = formatter;
    }

    void run(String[] queryOpts) throws IOException, org.apache.lucene.queryparser.classic.ParseException {
        run(queryOpts, defaultOut);
    }

    void run(String[] queryOpts, PrintStream out) throws IOException, org.apache.lucene.queryparser.classic.ParseException {
        if (formatter.getFormat() == Formatter.Format.TABULAR && fieldNames.isEmpty()) {
            // Unlike a SQL result set, Lucene docs from a single query (or %all) may
            // have different fields, so a tabular format won't make sense unless we
            // know the exact fields beforehand.
            throw new RuntimeException("--tabular requires --fields to be passed");
        }

        if (sortFields) {
            Collections.sort(fieldNames);
        }
        String opt = queryOpts[0]; 
        if ("%ids".equals(opt)) {
            List<String> ids = Lists.newArrayList(Arrays.copyOfRange(queryOpts, 1, queryOpts.length));
            dumpIds(ids.iterator());
        } else if ("%id-file".equals(opt)) {
            Iterator<String> iterator = new LineIterator(new BufferedReader(
                    new FileReader(queryOpts[1])));
            dumpIds(iterator);
        } else if ("%all".equals(opt)) {
            runQuery(null, out);
        } else if ("%enumerate-fields".equals(opt)) {
            for (String fieldName : allFieldNames) {
                out.println(fieldName);
            }
        } else if ("%count-fields".equals(opt)) {
            countFields();
        } else if ("%enumerate-terms".equals(opt)) {
            if (queryOpts.length != 2) {
                throw new RuntimeException("%enumerate-terms requires exactly one field.");
            }
            enumerateTerms(queryOpts[1]);
        } else if ("%script".equals(opt)) {
            if (queryOpts.length != 2) {
                throw new RuntimeException("%script requires exactly one arg.");
            }
            runScript(queryOpts[1]);
        } else {
            runQuery(queryOpts[0], out);
        }
    }

    // For now, script supports only -q (only simple, no % queries) and -o.
    // Might be nicer, eventually, to have all the other command line opts
    // apply for each script line, overriding the default command line level
    // setting.  But that can come later if we think it's useful.
    void runScript(String scriptPath) throws IOException, ParseException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
            new FileInputStream(scriptPath), Charsets.UTF_8))) {

            int lineno = 0;
            String line;
            while ((line = in.readLine()) != null) {
                lineno++;
                if (line.trim().isEmpty()) {
                    continue;
                }
                // This is Ant's Commandline class.  We need it because commons-cli
                // only has an interface from String[], where it expects the shell
                // has handled the quoting to give you this array.  But here we lines
                // in a script file and no shell to help us.
                Commandline cl = new Commandline(line);
                PrintStream out = defaultOut;
                String query = null;
                String[] args = cl.getCommandline();
                int i = 0;
                while (i < args.length) {
                    String arg = args[i];
                    if ("-o".equals(arg) || "-output".equals(arg) || "--output".equals(arg)) {
                        i++;
                        out = new PrintStream(new FileOutputStream(new File(args[i])), true);
                    } else if ("-q".equals(arg) || "-query".equals(arg) || "--query".equals(arg)) {
                        i++;
                        query = args[i];
                        if (query.startsWith("%")) {
                            throw new RuntimeException(String.format(
                                "%s:%d: script does not support %% queries", scriptPath, lineno));
                        }
                    } else {
                        throw new RuntimeException(String.format(
                            "%s:%d: script supports only -q and -o", scriptPath, lineno));
                    }
                    i++;
                }
                if (query == null || query.startsWith("%")) {
                    throw new RuntimeException(String.format(
                        "%s:%d: script line requires -q", scriptPath, lineno));
                }
                runQuery(query, out);
                if (out != defaultOut) {
                    out.close();
                }
            }
        }
    }

    private void dumpIds(Iterator<String> ids) throws IOException {
        docsPrinted = 0;
        while (ids.hasNext()) {
            for (String s : ids.next().split("\\s+")) {
                int id = Integer.parseInt(s);
                Document doc = indexReader.document(id);
                printDocument(doc, id, 1.0f, defaultOut);
            }
        }
    }

    private void enumerateTerms(String field) throws IOException {
        if (!allFieldNames.contains(field)) {
            throw new RuntimeException("Invalid field name: " + field);
        }
        List<LeafReaderContext> leaves = indexReader.leaves();
        TermsEnum termsEnum;
        boolean unindexedField = true;
        Map<String, Integer> termCountMap = new TreeMap<>();
        for (LeafReaderContext leaf : leaves) {
            Terms terms = leaf.reader().terms(field);
            if (terms == null) {
                continue;
            }
            unindexedField = false;
            termsEnum = terms.iterator();
            BytesRef bytesRef;
            while ((bytesRef = termsEnum.next()) != null) {
                String term = bytesRef.utf8ToString();
                if (termCountMap.containsKey(term)) {
                    termCountMap.put(term, termsEnum.docFreq() + termCountMap.get(term));
                } else {
                    termCountMap.put(term, termsEnum.docFreq());
                }
            }
        }
        if (unindexedField) {
            throw new RuntimeException("Unindexed field: " + field);
        }
        for (Map.Entry<String, Integer> entry : termCountMap.entrySet()) {
            defaultOut.println(entry.getKey() + " (" + entry.getValue() + ")");
        }
    }

    private void countFields() throws IOException {
        for (String field : allFieldNames) {
            List<LeafReaderContext> leaves = indexReader.leaves();
            Map<String, Integer> fieldCounts = new TreeMap<>();
            int count = 0;
            for (LeafReaderContext leaf : leaves) {
                Terms terms = leaf.reader().terms(field);
                if (terms == null) {
                    continue;
                }
                count += terms.getDocCount();
            }
            fieldCounts.put(field, count);
            for (Map.Entry<String, Integer> entry : fieldCounts.entrySet()) {
                defaultOut.println(entry.getKey() + ": " + entry.getValue());
            }
        }
    }

    private void runQuery(String queryString, final PrintStream out)
        throws IOException, org.apache.lucene.queryparser.classic.ParseException {
        final IndexSearcher searcher = new IndexSearcher(indexReader);
        docsPrinted = 0;
        Query query;
        if (queryString == null) {
            query = new MatchAllDocsQuery();
        } else {
            if (!queryString.contains(":") && defaultField == null) {
                throw new RuntimeException("query has no ':' and no query-field defined");
            }
            QueryParser queryParser = new QueryParser(defaultField, analyzer);
            queryParser.setLowercaseExpandedTerms(false);
            query = queryParser.parse(queryString).rewrite(indexReader);
            Set<Term> terms = Sets.newHashSet();
            query.createWeight(searcher, false).extractTerms(terms);
            List<String> invalidFieldNames = Lists.newArrayList();
            for (Term term : terms) {
                if (!allFieldNames.contains(term.field())) {
                    invalidFieldNames.add(term.field());
                }
            }
            if (!invalidFieldNames.isEmpty()) {
                throw new RuntimeException("Invalid field names: " + invalidFieldNames);
            }
        }

        final Set<String> fieldSet = Sets.newHashSet(fieldNames);

        // use a Collector instead of TopDocs for memory efficiency, especially
        // for the %all query
        class MyCollector extends SimpleCollector {
            private Scorer scorer;
            private long totalHits;

            @Override
            public void collect(int id) throws IOException {
                totalHits++;
                if (docsPrinted >= outputLimit) {
                    return;
                }

                Document doc = fieldSet.isEmpty() ? searcher.doc(id) : searcher.doc(id, fieldSet);
                boolean passedFilter = regexField == null;
                if (regexField != null) {
                    String value = doc.get(regexField);
                    if (value != null && regex.matcher(value).matches()) {
                        passedFilter = true;
                    }
                }
                if (passedFilter) {
                    float score = scorer.score();
                    printDocument(doc, id, score, out);
                }
            }

            @Override
            public boolean needsScores() {
                return true;
            }

            @Override
            public void setScorer(Scorer scorer) throws IOException {
                this.scorer = scorer;
            }
        }

        MyCollector collector = new MyCollector();
        searcher.search(query, collector);
        if (showHits) {
            out.println("totalHits: " + collector.totalHits);
            out.println();
        }
    }

    private String formatBinary(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("0x");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void printDocument(Document doc, int id, float score,
                               PrintStream out) {
        Multimap<String, String> data = ArrayListMultimap.create();
        List<String> orderedFieldNames = Lists.newArrayList();
        if (showId) {
            orderedFieldNames.add("<id>");
            data.put("<id>", Integer.toString(id));
        }
        if (showScore) {
            orderedFieldNames.add("<score>");
            data.put("<score>", Double.toString(score));
        }
        orderedFieldNames.addAll(fieldNames);

        Set<String> setFieldNames = Sets.newHashSet();
        if (fieldNames.isEmpty()) {
            for (IndexableField f : doc.getFields()) {
                if (!setFieldNames.contains(f.name())) {
                    orderedFieldNames.add(f.name());
                }
                setFieldNames.add(f.name());
            }
        } else {
            setFieldNames.addAll(fieldNames);
        }
        if (sortFields) {
            Collections.sort(orderedFieldNames);
        }

        for (IndexableField f : doc.getFields()) {
            if (setFieldNames.contains(f.name())) {
                if (f.stringValue() != null) {
                    data.put(f.name(), f.stringValue());
                } else if (f.binaryValue() != null) {
                    data.put(f.name(), formatBinary(f.binaryValue().bytes));
                } else {
                    data.put(f.name(), "null");
                }
            }
        }

        if (docsPrinted == 0
            && formatter.getFormat() == Formatter.Format.TABULAR
            && !formatter.suppressNames()) {

            out.println(Joiner.on('\t').join(orderedFieldNames));
        }

        String formatted = formatter.format(orderedFieldNames, data);
        if (!formatted.isEmpty()) {
            if (docsPrinted > 0 && formatter.getFormat() == Formatter.Format.MULTILINE) {
                out.println();
            }
            out.println(formatted);
            ++docsPrinted;
        }
    }

    private static void usage(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("LuceneQueryTool [options]", options);
        System.out.println();
    }

    private static Options createOptions() {
        Options options = new Options();
        Option option;

        option = new Option("i", "index", true, "index (required, multiple -i searches multiple indexes)");
        option.setRequired(true);
        options.addOption(option);

        option = new Option("q", "query", true,
            "(query | %all | %enumerate-fields "
                + "| %count-fields "
                + "| %enumerate-terms field "
                + "| %script scriptFile "
                + "| %ids id [id ...] | %id-file file) (required, scriptFile may contain -q and -o)");
        option.setRequired(true);
        option.setArgs(Option.UNLIMITED_VALUES);
        options.addOption(option);

        option = new Option(null, "regex", true, "filter query by regex, syntax is field:/regex/");
        option.setArgs(1);
        options.addOption(option);

        option = new Option(null, "fields", true, "fields to include in output (defaults to all)");
        option.setArgs(Option.UNLIMITED_VALUES);
        options.addOption(option);

        option = new Option(null, "sort-fields", false, "sort fields within document");
        options.addOption(option);

        option = new Option(null, "query-limit", true, "same as output-limit");
        option.setArgs(1);
        options.addOption(option);

        option = new Option(null, "output-limit", true, "max number of docs to output");
        option.setArgs(1);
        options.addOption(option);

        option = new Option(null, "analyzer", true,
                "for query, (KeywordAnalyzer | StandardAnalyzer) (defaults to KeywordAnalyzer)");
        option.setArgs(1);
        options.addOption(option);

        option = new Option(null, "query-field", true, "default field for query");
        option.setArgs(1);
        options.addOption(option);

        option = new Option(null, "show-id", false, "show Lucene document id in results");
        options.addOption(option);

        option = new Option(null, "show-score", false, "show score in results");
        options.addOption(option);

        option = new Option(null, "show-hits", false, "show total hit count");
        options.addOption(option);

        option = new Option(null, "suppress-names", false, "suppress printing of field names");
        options.addOption(option);

        option = new Option(null, "tabular", false, "print tabular output "
            + "(requires --fields)");
        options.addOption(option);

        option = new Option(null, "format", true, "output format (multiline | tabular | json | json-pretty) "
            + "(defaults to multiline)");
        option.setArgs(1);
        options.addOption(option);

        option = new Option("o", "output", true, "output file (defaults to standard output)");
        option.setArgs(1);
        options.addOption(option);

        return options;
    }

    // Workaround an apparent bug in commons-cli:  If an unknown option comes
    // after an option that accepts unlimited values, no error is produced.
    private static void validateOptions(Options options, String[] args) throws org.apache.commons.cli.ParseException {
        Set<String> optionNames = Sets.newHashSet();

        // non-generic forced by commons.cli api
        for (Object o : options.getOptions()) {
            Option option = (Option) o;
            optionNames.add(option.getLongOpt());
            String shortOpt = option.getOpt();
            if (shortOpt != null) {
                optionNames.add(shortOpt);
            }
        }
        for (String arg : args) {
            if (arg.startsWith("-")) {
                String argName = arg.replaceFirst("-+", "");
                if (!optionNames.contains(argName)) {
                    throw new org.apache.commons.cli.ParseException("Unrecognized option: " + arg);
                }
            }
        }
    }

    public static void main(String[] args) throws IOException, org.apache.lucene.queryparser.classic.ParseException {
        String charsetName = Charset.defaultCharset().name();
        if (!"UTF-8".equals(charsetName)) {
            // Really only a problem on mac, where the default charset is MacRoman,
            // and it cannot be changed via the system Locale.
            System.err.println(String.format("defaultCharset is %s, but we require UTF-8.", charsetName));
            System.err.println("Set -Dfile.encoding=UTF-8 on the Java command line, or");
            System.err.println("set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 in the environment.");
            System.exit(1);
        }

        Options options = LuceneQueryTool.createOptions();
        CommandLineParser parser = new GnuParser();
        CommandLine cmdline = null;
        try {
            cmdline = parser.parse(options, args);
            validateOptions(options, args);
        } catch (org.apache.commons.cli.ParseException e) {
            System.err.println(e.getMessage());
            usage(options);
            System.exit(1);
        }
        String[] remaining = cmdline.getArgs();
        if (remaining != null && remaining.length > 0) {
            System.err.println("unknown extra args found: " + Lists.newArrayList(remaining));
            usage(options);
            System.exit(1);
        }

        String[] indexPaths = cmdline.getOptionValues("index");
        IndexReader[] readers = new IndexReader[indexPaths.length];
        for (int i = 0; i < indexPaths.length; i++) {
            Path path = FileSystems.getDefault().getPath(indexPaths[i]);
            readers[i] = DirectoryReader.open(FSDirectory.open(path));
        }
        IndexReader reader = new MultiReader(readers, true);

        LuceneQueryTool that = new LuceneQueryTool(reader);

        String opt;
        opt = cmdline.getOptionValue("query-limit");
        if (opt != null) {
            that.setQueryLimit(Integer.parseInt(opt));
        }
        opt = cmdline.getOptionValue("output-limit");
        if (opt != null) {
            that.setOutputLimit(Integer.parseInt(opt));
        }
        opt = cmdline.getOptionValue("analyzer");
        if (opt != null) {
            that.setAnalyzer(opt);
        }
        opt = cmdline.getOptionValue("query-field");
        if (opt != null) {
            that.setDefaultField(opt);
        }
        opt = cmdline.getOptionValue("output");
        PrintStream out = null;
        if (opt != null) {
            out = new PrintStream(new FileOutputStream(new File(opt)), true);
            that.setOutputStream(out);
        }
        if (cmdline.hasOption("show-id")) {
            that.setShowId(true);
        }
        if (cmdline.hasOption("show-hits")) {
            that.setShowHits(true);
        }
        if (cmdline.hasOption("show-score")) {
            that.setShowScore(true);
        }
        if (cmdline.hasOption("sort-fields")) {
            that.setSortFields(true);
        }

        boolean suppressNames = cmdline.hasOption("suppress-names");
        Formatter.Format format = Formatter.Format.MULTILINE;
        opt = cmdline.getOptionValue("format");
        if (opt != null) {
            format = Formatter.Format.fromName(opt);
        }
        if (cmdline.hasOption("tabular")) {
            // compatibility option
            format = Formatter.Format.TABULAR;
        }
        that.setFormatter(Formatter.newInstance(format, suppressNames));

        String[] opts;
        opts = cmdline.getOptionValues("fields");
        if (opts != null) {
            that.setFieldNames(Lists.newArrayList(opts));
        }
        opt = cmdline.getOptionValue("regex");
        if (opt != null) {
            Pattern p = Pattern.compile("^(.*?):/(.*)/$");
            Matcher m = p.matcher(opt);
            if (m.matches()) {
                that.setRegex(m.group(1), Pattern.compile(m.group(2)));
            } else {
                System.err.println("Invalid regex, should be field:/regex/");
                usage(options);
                System.exit(1);
            }
        }
        opts = cmdline.getOptionValues("query");
        that.run(opts);
        if (out != null) {
            out.close();
        }
        reader.close();
    }
}
