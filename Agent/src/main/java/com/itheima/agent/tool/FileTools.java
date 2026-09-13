package com.itheima.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

/**
 * 文件操作工具，供大模型调用
 */
@Component
public class FileTools {

    @Tool(name = "readFile", description = "读取指定文本文件的内容")
    public String readFile(@ToolParam(description = "文件路径") String path) {
        try {
            return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "读取文件失败: " + e.getMessage();
        }
    }

    @Tool(name = "listFiles", description = "列出指定目录下的文件和子目录名称")
    public String listFiles(@ToolParam(description = "目录路径") String dir) {
        try {
            return Files.list(Paths.get(dir))
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "列出文件失败: " + e.getMessage();
        }
    }
}
