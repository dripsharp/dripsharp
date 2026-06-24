package com.example.wordcount;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public final class WordCounter {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private WordCounter() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: WordCounter <file>");
            System.exit(1);
        }

        Path input = Path.of(args[0]);
        String text = Files.readString(input);
        int words = countWords(text);
        System.out.println(words);
    }

    static int countWords(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }

        return WHITESPACE.split(trimmed).length;
    }
}
