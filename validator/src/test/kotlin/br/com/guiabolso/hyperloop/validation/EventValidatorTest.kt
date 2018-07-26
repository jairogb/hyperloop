package br.com.guiabolso.hyperloop.validation

import br.com.guiabolso.events.model.RequestEvent
import br.com.guiabolso.hyperloop.exceptions.InvalidInputException
import br.com.guiabolso.hyperloop.model.SchemaData
import br.com.guiabolso.hyperloop.schemas.CachedSchemaRepository
import br.com.guiabolso.hyperloop.schemas.SchemaKey
import br.com.guiabolso.hyperloop.schemas.SchemaRepository
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class EventValidatorTest {

    private lateinit var mockSchemaRepository: SchemaRepository<SchemaData>
    private lateinit var cachedSchemaRepository: CachedSchemaRepository<SchemaData>
    private lateinit var eventValidator: EventValidator

    private val schema = loadSchemaFromFile()
    @Before
    fun setUp() {
        mockSchemaRepository = mock()
        cachedSchemaRepository = CachedSchemaRepository(mockSchemaRepository)
        eventValidator = EventValidator(cachedSchemaRepository)
    }

    @Test
    fun `test succesfull validation`() {
        val payload = """
                        "users": [
                            {
                                "name": "Bruno",
                                "birthdate": "01/01/1990",
                                "gender": "male",
                                "id": 1111111111,
                                "married": false,
                                "height": 2.111,
                                "age": 28,
                                "friend":
                                {
                                    "name": "Carlos",
                                    "birthdate": "22/05/1990",
                                    "gender": "male",
                                    "id": 222222222,
                                    "married": true,
                                    "height": 3.111,
                                    "age": 28
                                }
                            },
                            {
                                "name": "Carlos",
                                "birthdate": "22/05/1990",
                                "gender": "male",
                                "id": 222222222,
                                "married": true,
                                "height": 3.111,
                                "age": 28
                            }
                        ],
                        "file": {
                            "name": "file",
                            "size": 123456.012456398725,
                            "quantity": 1233456877895613,
                            "owner": [
                                {
                                    "name": "Bruno",
                                    "birthdate": "01/01/1990",
                                    "gender": "male",
                                    "id": 1111111111,
                                    "married": false,
                                    "height": 2.111,
                                    "age": 28
                                },
                                {
                                    "name": "Carlos",
                                    "birthdate": "22/05/1990",
                                    "gender": "male",
                                    "id": 222222222,
                                    "married": true,
                                    "height": 3.111,
                                    "age": 28
                                }
                            ]
                        },
                        "name": "Thiago",
                        "x": "Thiago",
                        "y": "Thiago",
                        "o": "Thiago",
                        "k": "Thiago",
                        "l": "Thiago"
                """.trimIndent()
        whenever(mockSchemaRepository.get(SchemaKey("event_test", 1))).thenReturn(schema)
        val response = eventValidator.validate(newEvent("event_test", 1, payload))
        assertTrue(response.validationSuccess)
        assertTrue(response.validationErrors.isEmpty())
    }

    @Test
    fun `test wrong array type validation`() {
        val payload = """
                        "users": "notAnArray"
                """.trimIndent()
        whenever(mockSchemaRepository.get(SchemaKey("event_test", 1))).thenReturn(schema)
        val expectedErrors = mutableListOf<Throwable>(
                InvalidInputException("Array element 'users' is in the wrong format"),
                InvalidInputException("Element 'name' is required"))
        val response = eventValidator.validate(newEvent("event_test", 1, payload))

        assertFalse(response.validationSuccess)
        //TODO(check validationErrors in a better way)
        assertTrue(expectedErrors.first().message == response.validationErrors.first().message)
        assertTrue(expectedErrors.last().message == response.validationErrors.last().message)
    }

    @Test
    fun `test missing required field`() {
        whenever(mockSchemaRepository.get(SchemaKey("event_test", 1))).thenReturn(schema)
        val response = eventValidator.validate(newEvent("event_test", 1, ""))
        assertFalse(response.validationSuccess)
        assertTrue(response.validationErrors.first().message == "Element 'users' is required")
    }

    @Test
    fun `test with event name different from schema`() {
        whenever(mockSchemaRepository.get(SchemaKey("xpto", 1))).thenReturn(schema)
        val response = eventValidator.validate(newEvent("xpto", 1, ""))
        assertFalse(response.validationSuccess)
        assertTrue(response.validationErrors.first().message == "The event name 'xpto' is different from schema 'event_test'")
    }


    @Test
    fun `test with event version different from schema`() {
        whenever(mockSchemaRepository.get(SchemaKey("event_test", 3))).thenReturn(schema)
        val response = eventValidator.validate(newEvent("event_test", 3, ""))
        assertFalse(response.validationSuccess)
        assertTrue(response.validationErrors.first().message == "The event version '3' is different from schema '1'")
    }

    private fun newEvent(eventName: String, eventVersion: Int, payload: String): RequestEvent {
        val identity = JsonObject()
        identity.addProperty("userId", 1)

        val metadata = JsonObject()
        metadata.addProperty("origin", "origin")

        return RequestEvent(
                eventName,
                eventVersion,
                "Id",
                "flowId",
                JsonParser().parse("""{
                        $payload
                }"""),
                identity,
                JsonObject(),
                metadata
        )
    }

    private fun loadSchemaFromFile(): SchemaData {
        val schemaStr = File("src/main/resources/schema.yml").readText(Charsets.UTF_8)
        return mapper.readValue(schemaStr)
    }

    companion object {
        private val mapper = ObjectMapper(YAMLFactory())
                .registerModule(KotlinModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
    }
}