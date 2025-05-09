/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.utils;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

/** Compress utils. */
public class CompressUtils {

    public static void gzipCompressFile(String src, String dest) throws IOException {
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dest);
        GZIPOutputStream gzipOs = new GZIPOutputStream(fos);
        byte[] buffer = new byte[1024];
        int bytesRead;
        while (true) {
            bytesRead = fis.read(buffer);
            if (bytesRead == -1) {
                fis.close();
                gzipOs.close();
                return;
            }

            gzipOs.write(buffer, 0, bytesRead);
        }
    }
}
