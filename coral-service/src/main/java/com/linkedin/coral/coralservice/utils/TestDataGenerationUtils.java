/**
 * Copyright 2025-2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.coralservice.utils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.calcite.rel.RelNode;

import com.linkedin.coral.datagen.domain.CastRegexTransformer;
import com.linkedin.coral.datagen.domain.DomainInferenceProgram;
import com.linkedin.coral.datagen.domain.GenerateTestDataProgram;
import com.linkedin.coral.datagen.domain.LowerRegexTransformer;
import com.linkedin.coral.datagen.domain.PlusRegexTransformer;
import com.linkedin.coral.datagen.domain.SubstringRegexTransformer;
import com.linkedin.coral.datagen.domain.TimesRegexTransformer;
import com.linkedin.coral.hive.hive2rel.HiveToRelConverter;
import com.linkedin.coral.trino.trino2rel.TrinoToRelConverter;

import static com.linkedin.coral.coralservice.utils.CoralProvider.*;


public class TestDataGenerationUtils {

  public static List<Map<String, Object>> generateTestData(String query, String sourceLanguage, int numRows) {
    RelNode relNode = convertQueryToRelNode(query, sourceLanguage);

    DomainInferenceProgram domainInferenceProgram =
        new DomainInferenceProgram(Arrays.asList(new LowerRegexTransformer(), new SubstringRegexTransformer(),
            new PlusRegexTransformer(), new TimesRegexTransformer(), new CastRegexTransformer()));
    GenerateTestDataProgram testDataProgram = new GenerateTestDataProgram(domainInferenceProgram);

    return testDataProgram.generateTestData(relNode, numRows);
  }

  private static RelNode convertQueryToRelNode(String query, String sourceLanguage) {
    if (sourceLanguage.equalsIgnoreCase("hive") || sourceLanguage.equalsIgnoreCase("spark")) {
      return new HiveToRelConverter(hiveMetastoreClient).convertSql(query);
    } else if (sourceLanguage.equalsIgnoreCase("trino")) {
      return new TrinoToRelConverter(hiveMetastoreClient).convertSql(query);
    } else {
      throw new IllegalArgumentException("Unsupported source language: " + sourceLanguage);
    }
  }

  public static boolean isValidSourceLanguage(String sourceLanguage) {
    return sourceLanguage != null && (sourceLanguage.equalsIgnoreCase("hive")
        || sourceLanguage.equalsIgnoreCase("trino") || sourceLanguage.equalsIgnoreCase("spark"));
  }
}
