/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.execution;

import com.google.common.util.concurrent.ListenableFuture;
import io.trino.Session;
import io.trino.metadata.Metadata;
import io.trino.metadata.QualifiedObjectName;
import io.trino.metadata.TableHandle;
import io.trino.security.AccessControl;
import io.trino.spi.connector.CatalogSchemaName;
import io.trino.spi.security.Privilege;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.Grant;
import io.trino.sql.tree.GrantOnType;
import io.trino.transaction.TransactionManager;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static io.trino.metadata.MetadataUtil.createCatalogSchemaName;
import static io.trino.metadata.MetadataUtil.createPrincipal;
import static io.trino.metadata.MetadataUtil.createQualifiedObjectName;
import static io.trino.spi.StandardErrorCode.INVALID_PRIVILEGE;
import static io.trino.spi.StandardErrorCode.SCHEMA_NOT_FOUND;
import static io.trino.spi.StandardErrorCode.TABLE_NOT_FOUND;
import static io.trino.sql.analyzer.SemanticExceptions.semanticException;

public class GrantTask
        implements DataDefinitionTask<Grant>
{
    @Override
    public String getName()
    {
        return "GRANT";
    }

    @Override
    public ListenableFuture<?> execute(Grant statement, TransactionManager transactionManager, Metadata metadata, AccessControl accessControl, QueryStateMachine stateMachine, List<Expression> parameters)
    {
        if (statement.getType().filter(GrantOnType.SCHEMA::equals).isPresent()) {
            executeGrantOnSchema(stateMachine.getSession(), statement, metadata, accessControl);
        }
        else {
            executeGrantOnTable(stateMachine.getSession(), statement, metadata, accessControl);
        }
        return immediateFuture(null);
    }

    private void executeGrantOnSchema(Session session, Grant statement, Metadata metadata, AccessControl accessControl)
    {
        CatalogSchemaName schemaName = createCatalogSchemaName(session, statement, Optional.of(statement.getName()));

        if (!metadata.schemaExists(session, schemaName)) {
            throw semanticException(SCHEMA_NOT_FOUND, statement, "Schema '%s' does not exist", schemaName);
        }

        Set<Privilege> privileges = parseStatementPrivileges(statement);
        for (Privilege privilege : privileges) {
            accessControl.checkCanGrantSchemaPrivilege(session.toSecurityContext(), privilege, schemaName, createPrincipal(statement.getGrantee()), statement.isWithGrantOption());
        }

        metadata.grantSchemaPrivileges(session, schemaName, privileges, createPrincipal(statement.getGrantee()), statement.isWithGrantOption());
    }

    private void executeGrantOnTable(Session session, Grant statement, Metadata metadata, AccessControl accessControl)
    {
        QualifiedObjectName tableName = createQualifiedObjectName(session, statement, statement.getName());
        Optional<TableHandle> tableHandle = metadata.getTableHandle(session, tableName);
        if (tableHandle.isEmpty()) {
            throw semanticException(TABLE_NOT_FOUND, statement, "Table '%s' does not exist", tableName);
        }

        Set<Privilege> privileges = parseStatementPrivileges(statement);

        for (Privilege privilege : privileges) {
            accessControl.checkCanGrantTablePrivilege(session.toSecurityContext(), privilege, tableName, createPrincipal(statement.getGrantee()), statement.isWithGrantOption());
        }

        metadata.grantTablePrivileges(session, tableName, privileges, createPrincipal(statement.getGrantee()), statement.isWithGrantOption());
    }

    private static Set<Privilege> parseStatementPrivileges(Grant statement)
    {
        Set<Privilege> privileges;
        if (statement.getPrivileges().isPresent()) {
            privileges = statement.getPrivileges().get().stream()
                    .map(privilege -> parsePrivilege(statement, privilege))
                    .collect(toImmutableSet());
        }
        else {
            // All privileges
            privileges = EnumSet.allOf(Privilege.class);
        }
        return privileges;
    }

    private static Privilege parsePrivilege(Grant statement, String privilegeString)
    {
        for (Privilege privilege : Privilege.values()) {
            if (privilege.name().equalsIgnoreCase(privilegeString)) {
                return privilege;
            }
        }

        throw semanticException(INVALID_PRIVILEGE, statement, "Unknown privilege: '%s'", privilegeString);
    }
}