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

package org.apache.paimon.spark

import org.apache.paimon.predicate.Predicate
import org.apache.paimon.table.Table

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.connector.read.LocalScan
import org.apache.spark.sql.types.StructType

/** A scan does not require [[RDD]] to execute */
case class PaimonLocalScan(
    rows: Array[InternalRow],
    readSchema: StructType,
    table: Table,
    filters: Array[Predicate])
  extends LocalScan {

  override def description(): String = {
    val pushedFiltersStr = if (filters.nonEmpty) {
      ", PushedFilters: [" + filters.mkString(",") + "]"
    } else {
      ""
    }
    s"PaimonLocalScan: [${table.name}]" + pushedFiltersStr
  }
}
