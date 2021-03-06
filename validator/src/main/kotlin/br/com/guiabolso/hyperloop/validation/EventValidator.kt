package br.com.guiabolso.hyperloop.validation

import br.com.guiabolso.events.model.RequestEvent
import br.com.guiabolso.hyperloop.exceptions.InvalidInputException
import br.com.guiabolso.hyperloop.exceptions.WrongSchemaFormatException
import br.com.guiabolso.hyperloop.model.SchemaData
import br.com.guiabolso.hyperloop.schemas.CachedSchemaRepository
import br.com.guiabolso.hyperloop.schemas.SchemaDataRepository
import br.com.guiabolso.hyperloop.schemas.SchemaKey
import br.com.guiabolso.hyperloop.schemas.SchemaRepository
import br.com.guiabolso.hyperloop.validation.types.ArrayType
import br.com.guiabolso.hyperloop.validation.types.DateType
import br.com.guiabolso.hyperloop.validation.types.PrimitiveType
import br.com.guiabolso.hyperloop.validation.types.SchemaNodeTypeParser
import br.com.guiabolso.hyperloop.validation.types.SchemaType
import br.com.guiabolso.hyperloop.validation.types.UserDefinedType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import com.google.gson.JsonElement
import java.text.ParseException
import java.text.SimpleDateFormat
import kotlin.collections.MutableMap.MutableEntry

typealias InputSchemaSpec = MutableIterator<MutableEntry<String, JsonNode>>

class EventValidator(
        schemaRepository: SchemaRepository<String>
) : Validator {

    private val cachedSchemaRepository: CachedSchemaRepository<SchemaData>

    init {
        val schemaDataRepository = SchemaDataRepository(schemaRepository)
        cachedSchemaRepository = CachedSchemaRepository(schemaDataRepository)
    }

    override fun validate(event: RequestEvent): ValidationResult {
        val validationResult = ValidationResult(false, mutableListOf(), mutableListOf())
        val encryptedElementPath = mutableListOf<String>()
        val schemaKey = SchemaKey(event.name, event.version)
        val schemaData = cachedSchemaRepository.get(schemaKey)

        if (schemaData.event.name != event.name)
            validationResult.validationErrors.add(InvalidInputException("The event name '${event.name}' is different from schema '${schemaData.event.name}'"))
        if (schemaData.event.version != event.version)
            validationResult.validationErrors.add(InvalidInputException("The event version '${event.version}' is different from schema '${schemaData.event.version}'"))


        val schemaPayloadSpec = schemaData.validation["payload"]?.fields()
        if (schemaPayloadSpec == null)
            validationResult.validationErrors.add(WrongSchemaFormatException("The schema '$schemaKey' has no payload"))
        else {
            val eventPayloadContent = event.payload
            iterateSchemaElements(schemaPayloadSpec, schemaData, eventPayloadContent, validationResult, encryptedElementPath)
        }

        schemaData.validation["identity"]?.fields()?.let { schemaIdentitySpec ->
            val eventIdentityContent = event.identity
            val userId = eventIdentityContent.asJsonObject["userId"]
            if (userId == null)
                validationResult.validationErrors.add(WrongSchemaFormatException("The event '${event.name}' has no userId"))
            else
                this.iterateSchemaElements(schemaIdentitySpec, schemaData, eventIdentityContent, validationResult, encryptedElementPath)
        }

        schemaData.validation["metadata"]?.fields()?.let { schemaMetadataSpec ->
            val eventMetadataContent = event.metadata
            val origin = eventMetadataContent.asJsonObject["origin"]
            if (origin == null)
                validationResult.validationErrors.add(WrongSchemaFormatException("The event '${event.name}' has no origin"))
            else
                this.iterateSchemaElements(schemaMetadataSpec, schemaData, eventMetadataContent, validationResult, encryptedElementPath)
        }

        if (validationResult.validationErrors.isEmpty()) validationResult.validationSuccess = true
        return validationResult
    }

    private fun iterateSchemaElements(
            schemaNodeSpec: InputSchemaSpec,
            schemaData: SchemaData,
            eventNode: JsonElement,
            validationResult: ValidationResult,
            encryptedElementPath: MutableList<String>
    ) {
        schemaNodeSpec.forEach { (key, node) ->
            val eventNodeElement = eventNode.asJsonObject[key]
            try {
                val expectedType = SchemaNodeTypeParser.getSchemaNodeType(schemaData, key, node)
                this.validateRequiredElement(key, node, eventNodeElement)
                eventNodeElement?.let {
                    this.validateByType(node, expectedType, schemaData, eventNodeElement, false, validationResult, encryptedElementPath)
                }
            } catch (exception: Exception) {
                validationResult.validationErrors.add(exception)
            }
        }
    }

    private fun validateByType(
            schemaNodeSpec: JsonNode,
            schemaType: SchemaType,
            schemaData: SchemaData,
            inputNode: JsonElement,
            isArrayElement: Boolean,
            validationResult: ValidationResult,
            encryptedElementPath: MutableList<String>
    ) {
        try {
            if (!inputNode.isJsonNull && this.isEncrypted(schemaNodeSpec)) {
                encryptedElementPath.add(schemaType.nodeKey)
                if (!validationResult.encryptedFields.contains(getListString(encryptedElementPath)))
                    validationResult.encryptedFields.add(getListString(encryptedElementPath))
                encryptedElementPath.remove(encryptedElementPath.lastOrNull())
            }
            when (schemaType) {
                is ArrayType -> this.validateArrayElement(schemaNodeSpec, schemaType, inputNode, schemaData, validationResult, encryptedElementPath)
                is DateType -> this.validateDateElement(schemaType, inputNode)
                is UserDefinedType -> {
                    if (isArrayElement)
                        encryptedElementPath.add("${schemaType.nodeKey}[*]")
                    else
                        encryptedElementPath.add(schemaType.nodeKey)

                    val currentTypeSpec = schemaType.userType.fields()
                    iterateSchemaElements(currentTypeSpec, schemaData, inputNode, validationResult, encryptedElementPath)
                    encryptedElementPath.remove(encryptedElementPath.lastOrNull())
                }
                is PrimitiveType -> schemaType.type.verifyType(inputNode.asJsonPrimitive)
            }
        } catch (exception: Exception) {
            validationResult.validationErrors.add(exception)
        }
    }

    private fun isEncrypted(specNode: JsonNode): Boolean {
        val schemaNodeAttributes = getSchemaNodeAttributes(specNode) ?: return false
        return TextNode("encrypted") in schemaNodeAttributes
    }

    private fun validateRequiredElement(
            nodeKey: String,
            specNode: JsonNode,
            inputElement: JsonElement?
    ) {
        if (isRequired(specNode) && (inputElement == null || inputElement.isJsonNull))
            throw InvalidInputException("Element '$nodeKey' is required")
    }

    private fun isRequired(specNode: JsonNode): Boolean {
        val schemaNodeAttributes = getSchemaNodeAttributes(specNode) ?: return false
        return TextNode("required") in schemaNodeAttributes
    }

    private fun getSchemaNodeAttributes(specNode: JsonNode): JsonNode? {
        return specNode.get("is")
    }

    private fun validateArrayElement(
            schemaNodeSpec: JsonNode,
            type: ArrayType,
            arrayElement: JsonElement,
            schemaData: SchemaData,
            validationResult: ValidationResult,
            encryptedElementPath: MutableList<String>
    ) {
        if (!arrayElement.isJsonArray) {
            throw InvalidInputException("Array element '${type.nodeKey}' is in the wrong format")
        }
        arrayElement.asJsonArray.forEach {
            validateByType(schemaNodeSpec, type.contentType, schemaData, it, true, validationResult, encryptedElementPath)
        }
    }

    private fun validateDateElement(type: DateType, dateElement: JsonElement) {
        val simpleDateFormat = SimpleDateFormat(type.format)
        try {
            simpleDateFormat.parse(dateElement.asString)
        } catch (e: ParseException) {
            throw InvalidInputException("Date Element '${dateElement.asString}' is not in the format '${type.format}'")
        }
    }

    private fun getListString(list: List<String>) = list.joinToString(".")
}
