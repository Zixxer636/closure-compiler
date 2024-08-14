package com.google.javascript.jscomp;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.CaseFormat;
import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.CommandLineRunner;
import com.google.javascript.jscomp.JsMessage;
import com.google.javascript.jscomp.JsMessageExtractor;
import com.google.javascript.jscomp.SourceFile;
import com.google.javascript.jscomp.GoogleJsMessageIdGenerator;

/**
 * ExtractMessages will use the closure compiler jar and extracts messages from the given javascript files and writes them to the output php file
 * 
 * java -classpath .:closure-compiler-one-v20231112.jar ExtractMessages.java \
 *                   --js '../js/classes/**.js' \
 *                   --js '!../js/classes/livechat/**' \
 *                   --output '../php/application/translations/sources/javascript-runtime.php'
 */
public class ExtractMessages {
    public ExtractMessages(AbstractCompiler compiler, List<String> jsSources, String outputFile) {
        try {
            File f = new File(outputFile);
            if(!f.exists() || f.isDirectory()) { 
                compiler.report(JSError.make(AbstractCompiler.READ_ERROR, outputFile, f.isDirectory() ? "Path is a folder" : "File does not exist"));
                return;
            }
             
            Collection<SourceFile> jsFiles = this.getJsFiles(jsSources);

            StringBuilder buffer = new StringBuilder();
            buffer.append("$translations = array(\n");

            JsMessageExtractor extractor = new JsMessageExtractor(new GoogleJsMessageIdGenerator(""));

            Collection<JsMessage> messages = extractor.extractMessages(jsFiles);
            Map<String, JsMessage> messageMap = new LinkedHashMap<>(messages.size());

            // First loop, will overwrite duplicates and only stores the last one
            for (JsMessage message : messages) {
                messageMap.put(message.getId(), message);
            }

            for (Map.Entry<String, JsMessage> entry : messageMap.entrySet()) {
                String messageId = entry.getKey();
                JsMessage message = entry.getValue();
                long longId = Long.parseUnsignedLong(message.getId());

                this.appendMessageToBuffer(buffer, message, longId);
            }

            buffer.append("\n);");

            String javascriptPHPData = buffer.toString();
            
            this.replaceFileContent(outputFile, javascriptPHPData);
        } catch (Exception e) {
            e.printStackTrace();

            System.exit(1);
        }
    }

    private Collection<SourceFile> getJsFiles(List<String> jsPaths) throws IOException {
        List<String> jsFileNames = CommandLineRunner.findJsFiles(jsPaths);
        Collection<SourceFile> jsFiles = new ArrayList<>(jsFileNames.size());

        for (String jsFile : jsFileNames) {
            jsFiles.add(SourceFile.fromFile(jsFile));
        }

        return jsFiles;
    }

    private void appendMessageToBuffer(StringBuilder buffer, JsMessage message, long longId) {
        buffer.append("\t/*\n");
        buffer.append("\t").append(message.getDesc()).append("\n");
        buffer.append("\tKey: ").append(message.getKey()).append("\n");
        buffer.append("\tId: ").append(message.getId()).append("\n");
        buffer.append("\t*/\n");

        buffer.append("\t\"").append(getPackedId(longId)).append("\" => _(\"");

        StringBuilder contentBuffer = new StringBuilder();
        for (JsMessage.Part p : message.getParts()) {
            if (p.isPlaceholder()) {
                // startName => START_NAME
                String name = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, p.getJsPlaceholderName());
                contentBuffer.append("<ph name=\"").append(name).append("\" />");
            } else {
                contentBuffer.append(escape(p.getString()));
            }
        }

        buffer.append(contentBuffer.toString().replace("\"", "\\\""));
        buffer.append("\"),\n\n");
    }

    private void replaceFileContent(String outputFilePath, String javascriptPHPData) throws IOException {
        String fileContent = new String(Files.readAllBytes(Paths.get(outputFilePath)));
        Pattern pattern = Pattern.compile("/\\* START CONTENT \\*/.+/\\* END CONTENT \\*/", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(fileContent);

        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement("/* START CONTENT */\n" + javascriptPHPData + "\n/* END CONTENT */"));
        }
        matcher.appendTail(result);
        Files.write(Paths.get(outputFilePath), result.toString().getBytes(), StandardOpenOption.TRUNCATE_EXISTING);
    }

    protected static String escape(CharSequence value) {
        return value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static String getPackedId(long id) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(id);
        byte[] longBytes = buffer.array();

        String s = new String(Base64.getEncoder().encode(longBytes));

        int index;
        for (index = s.length() - 1; index >= 0; index--) {
            if (s.charAt(index) != '=') {
                break;
            }
        }

        return s.substring(0, index + 1);
    }
}
