package com.ztdrop.android

import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    check(formatFileSize(0) == "0 KB")
    check(formatFileSize(512) == "<1 KB")
    check(formatFileSize(1024) == "1 KB")
    check(formatFileSize(1024L * 1024) == "1 MB")
    check(formatFileSize(1024L * 1024 * 1024) == "1 GB")
    val html = renderDownloadPage("__FILE_NAME__|__FILE_SIZE__|__TOKEN__", "<资料&报告>.zip", 1284L * 1024 * 1024 / 10, "abc")
    check(html == "&lt;资料&amp;报告&gt;.zip|128.4 MB|abc")
    check(renderDownloadPage("__FILE_NAME__|__STATUS_SCRIPT__", "__STATUS_SCRIPT__", 1, "abc") == "__STATUS_SCRIPT__|")
    check(renderDownloadPage("__FILE_SIZE__", "x", 1, "abc") == "&lt;1 KB")
    val template = Files.readString(Path.of(args.single()))
    val page = renderDownloadPage(template, "测试文件.zip", 2L * 1024 * 1024 * 1024, "abc")
    check(page.contains("class=\"name\">测试文件.zip</span><span class=\"size\">2 GB</span>"))
    check(page.contains("href=\"/download/abc\""))
    check(!page.contains("单个文件") && !page.contains("__STATUS_SCRIPT__"))
}
