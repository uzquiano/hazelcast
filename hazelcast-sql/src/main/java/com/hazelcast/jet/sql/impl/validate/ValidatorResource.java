/*
 * Copyright 2021 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.sql.impl.validate;

import org.apache.calcite.runtime.Resources;
import org.apache.calcite.runtime.Resources.BaseMessage;
import org.apache.calcite.runtime.Resources.ExInst;
import org.apache.calcite.sql.validate.SqlValidatorException;

public interface ValidatorResource {

    ValidatorResource RESOURCE = Resources.create(ValidatorResource.class);

    @BaseMessage("{0}")
    ExInst<SqlValidatorException> error(String s);

    @BaseMessage("{0} not supported")
    ExInst<SqlValidatorException> notSupported(String name);

    @BaseMessage("Grouping/aggregations not supported for a streaming query")
    ExInst<SqlValidatorException> streamingAggregationsNotSupported();

    @BaseMessage("Sub-query not supported on the right side of a join")
    ExInst<SqlValidatorException> joiningSubqueryNotSupported();

    @BaseMessage("VALUES clause not supported on the right side of a join")
    ExInst<SqlValidatorException> joiningValuesNotSupported();

    @BaseMessage("UPDATE FROM SELECT not supported")
    ExInst<SqlValidatorException> updateFromSelectNotSupported();

    @BaseMessage("You must use CREATE JOB statement for a streaming DML query")
    ExInst<SqlValidatorException> mustUseCreateJob();

    @BaseMessage("Unknown argument name ''{0}''")
    ExInst<SqlValidatorException> unknownArgumentName(String name);

    @BaseMessage("Sorting is not supported for a streaming query")
    ExInst<SqlValidatorException> streamingSortingNotSupported();

    static String imapNotMapped(String originalMessage, String identifier, String suggestion) {
        return originalMessage + " If you want to use the IMap named '" + identifier + "', execute this command first: "
                + suggestion;
    }
}