package com.example.coachserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `applySchema` runs on every boot, so a malformed split here is a crash loop, not a test failure.
 * These run without a database.
 */
class SchemaStatementsTest {

    @Test
    fun `a semicolon inside a comment does not become a statement`() {
        val sql = """
            CREATE TABLE t (id INT);

            -- This comment identifies something exactly; and then keeps going.
            ALTER TABLE t ADD COLUMN eco TEXT;
        """.trimIndent()

        val statements = splitStatements(sql)

        assertEquals(2, statements.size, "Comment text leaked into the statement list: $statements")
        assertTrue(statements[0].startsWith("CREATE TABLE"))
        assertTrue(statements[1].startsWith("ALTER TABLE"))
    }

    @Test
    fun `the checked in schema parses into executable statements only`() {
        val schema = checkNotNull(javaClass.getResource("/schema.sql")).readText()

        val statements = splitStatements(schema)

        assertTrue(statements.isNotEmpty())
        val leadingWords = setOf("CREATE", "ALTER", "DROP", "INSERT", "UPDATE", "COMMENT", "GRANT")
        val invalid = statements.filterNot { statement ->
            leadingWords.any { statement.uppercase().startsWith(it) }
        }
        assertTrue(
            invalid.isEmpty(),
            "These fragments would be sent to Postgres as statements: $invalid",
        )
    }
}
