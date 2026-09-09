package com.flino.watch;

import java.nio.file.Path;

public record FileEvent(String sourceName, Path path) {
}
