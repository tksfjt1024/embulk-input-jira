module Jira
  class Issue
    attr_reader :fields

    def initialize(attributes)
      @fields = attributes.fetch("fields")
    end

    def [](attribute)
      fields = @fields
      attribute.split('.').each do |chunk|
        fields = fields[chunk]
        return fields if fields.nil?
      end

      if fields.is_a?(Array) || fields.is_a?(Hash)
        fields.to_json.to_s
      else
        fields
      end
    end

    def to_record
      record = {}

      fields.each_pair do |key, value|
        record_key = key
        record_value = value.to_json.to_s

        if value.is_a?(String)
          record_value = value

        elsif value.is_a?(Hash)
          if value.keys.include?("name")
            record_key += ".name"
            record_value = value["name"]
          elsif value.keys.include?("id")
            record_key += ".id"
            record_value = value["id"]
          end
        end

        record[record_key] = record_value
      end

      record
    end
  end
end
