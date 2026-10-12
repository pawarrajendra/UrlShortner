#!/usr/bin/env ruby
# Phase 0 checks with Ruby standard libraries. Not a full OpenAPI validator.
require 'yaml'
require 'date'
require 'time'
require 'uri'

ROOT = File.expand_path('..', __dir__)
phase2 = ARGV.include?('--phase2')
phase3 = ARGV.include?('--phase3')
phase4 = ARGV.include?('--phase4')
phase5 = ARGV.include?('--phase5')
source = File.read(File.join(ROOT, phase2 ? 'docs/openapi-phase2.yaml' : phase3 ? 'docs/openapi-phase3.yaml' : phase4 ? 'docs/openapi-phase4.yaml' : phase5 ? 'docs/openapi-phase5.yaml' : 'openapi.yaml'))

def ensure_that(condition, message)
  raise message unless condition
end

# Fail rather than silently accepting duplicate YAML mapping keys.
def check_keys(node)
  if node.is_a?(Psych::Nodes::Mapping)
    keys = node.children.each_slice(2).map { |pair| pair[0].value }
    ensure_that(keys.uniq.length == keys.length, 'Duplicate YAML mapping keys')
  end
  Array(node.children).each { |child| check_keys(child) } if node.respond_to?(:children)
end
check_keys(Psych.parse_stream(source))
SPEC = YAML.safe_load(source)

def resolve(value)
  return value unless value.is_a?(Hash) && value.key?('$ref')
  parts = value.fetch('$ref').split('/')
  ensure_that(parts.shift == '#', 'Only local contract refs expected')
  parts.reduce(SPEC) { |current, key| current.fetch(key.gsub('~1', '/').gsub('~0', '~')) }
end

def walk_refs(value)
  case value
  when Hash
    resolve(value) if value.key?('$ref')
    value.each_value { |child| walk_refs(child) }
  when Array
    value.each { |child| walk_refs(child) }
  end
end

def example_matches(schema, value, location)
  schema = resolve(schema)
  return if value.nil? && schema['nullable']
  valid_type = case schema['type']
               when 'object' then value.is_a?(Hash)
               when 'array' then value.is_a?(Array)
               when 'string' then value.is_a?(String)
               when 'integer' then value.is_a?(Integer)
               when 'boolean' then value == true || value == false
               else false
               end
  ensure_that(valid_type, "#{location}: wrong example type")
  ensure_that(schema['enum'].include?(value), "#{location}: enum mismatch") if schema['enum']
  if value.is_a?(Hash)
    props = schema.fetch('properties')
    Array(schema['required']).each { |key| ensure_that(value.key?(key), "#{location}: missing #{key}") }
    ensure_that((value.keys - props.keys).empty?, "#{location}: extra property") if schema['additionalProperties'] == false
    value.each { |key, child| example_matches(props.fetch(key), child, "#{location}.#{key}") }
  elsif value.is_a?(Array)
    ensure_that(value.length <= schema['maxItems'], "#{location}: array too long") if schema['maxItems']
    value.each_with_index { |child, i| example_matches(schema.fetch('items'), child, "#{location}[#{i}]") }
  elsif value.is_a?(String)
    ensure_that(value.length >= schema['minLength'], "#{location}: string too short") if schema['minLength']
    ensure_that(value.length <= schema['maxLength'], "#{location}: string too long") if schema['maxLength']
    ensure_that(Regexp.new(schema['pattern']).match?(value), "#{location}: pattern mismatch") if schema['pattern']
    case schema['format']
    when 'date' then Date.iso8601(value)
    when 'date-time' then Time.iso8601(value)
    when 'uuid'
      ensure_that(/\A[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\z/.match?(value), "#{location}: UUID mismatch")
    when 'uri' then ensure_that(URI.parse(value).absolute?, "#{location}: nonabsolute URI")
    end
  elsif value.is_a?(Integer)
    ensure_that(value >= schema['minimum'], "#{location}: below minimum") if schema['minimum']
  end
end

ensure_that(SPEC['openapi'] == '3.0.3', 'Unexpected OpenAPI version')
ensure_that(SPEC.dig('info', 'version'), 'Missing contract version')
walk_refs(SPEC)
expected = {
  '/api/v1/links' => %w[post],
  '/api/v1/links/{code}' => %w[get delete],
  '/api/v1/links/{code}/stats' => %w[get],
  '/r/{code}' => %w[get head],
  '/health/live' => %w[get],
  '/health/ready' => %w[get]
}
expected.delete('/api/v1/links/{code}/stats') if phase2 || phase3
ensure_that(SPEC.fetch('paths').keys.sort == expected.keys.sort, 'Unexpected route surface')
ids = []
expected.each do |path, methods|
  entry = SPEC.fetch('paths').fetch(path)
  ensure_that((entry.keys - ['parameters']).sort == methods.sort, "#{path}: methods mismatch")
  methods.each do |method|
    op = entry.fetch(method)
    ids << op.fetch('operationId')
    ensure_that(op['x-implementation-phase'], "#{path}: missing phase")
    ensure_that(op.fetch('security', SPEC.fetch('security', [])) == [], "#{path}: API access must be public")
    ensure_that(!op.fetch('responses').key?('401'), "#{path}: unexpected credential response")
    ensure_that(!op.fetch('responses').key?('429'), 'Rate limiting must remain optional')
    body = op.dig('requestBody', 'content', 'application/json')
    example_matches(body.fetch('schema'), body['example'], "#{path} request") if body && body.key?('example')
    op.fetch('responses').each do |status, resp|
      resp = resolve(resp)
      ensure_that(resp['description'].is_a?(String), "#{path}: response description missing")
      ensure_that(!resp.key?('content'), "#{path}: HEAD/204 body forbidden") if method == 'head' || status == '204'
      resp.fetch('headers').each do |name, header|
        header = resolve(header)
        example_matches(header.fetch('schema'), header['example'], "#{path} #{name}") if header.key?('example')
      end
      (resp['content'] || {}).each_value do |media|
        example_matches(media.fetch('schema'), media['example'], "#{path} #{status}") if media.key?('example')
      end
    end
  end
end
ensure_that(ids.uniq.length == ids.length, 'Duplicate operation IDs')
stats = SPEC.dig('paths', '/api/v1/links/{code}/stats', 'get', 'responses', '200', 'content', 'application/json', 'example')
if stats
ensure_that(stats['daily'].sum { |bucket| bucket['observedRedirects'] } == stats['observedRedirects'], 'Stats example total mismatch')
dates = (Date.iso8601(stats['from'])...Date.iso8601(stats['to'])).map(&:iso8601)
ensure_that(stats['daily'].map { |bucket| bucket['date'] } == dates, 'Stats example bucket range mismatch')
else
  ensure_that(phase2 || phase3, 'Missing planned stats example')
  %w[CreateLinkRequest LinkResponse].each do |name|
    ensure_that(SPEC.dig('components', 'schemas', name, 'properties').key?('expiresAt') == phase3, 'Expiry property must match phase checkpoint')
  end
end
checks = phase2 ? 'P2 expiry exclusion' : phase3 ? 'P3 expiry inclusion' : 'stats totals'
puts "PASS: #{ids.length} operations, #{SPEC.dig('components', 'schemas').length} DTO schemas; local refs, duplicate keys, public access, examples, headers, HEAD/204, phase markers, #{checks}, and optional rate-limit scope checked."
puts 'LIMITATION: static structural/example checks only; not full OpenAPI conformance, SQL execution, or runtime API verification.'
