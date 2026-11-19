create table if not exists project (
    id varchar(64) primary key,
    name varchar(200) not null,
    description varchar(2000) not null default '',
    status varchar(32) not null,
    deleted boolean not null default false,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

alter table project add column if not exists owner_id varchar(64) not null default 'default';
create index if not exists idx_project_owner on project(owner_id, updated_at);

create table if not exists assistant_session (
    id varchar(64) primary key,
    project_id varchar(64) not null,
    default_user_id varchar(64) not null default 'default_user',
    title varchar(300) not null,
    summary varchar(4000) not null default '',
    status varchar(32) not null,
    last_context_version integer not null default 0,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_session_project foreign key (project_id) references project(id)
);

update assistant_session set default_user_id = 'default' where default_user_id = 'default_user';

create table if not exists assistant_message (
    id varchar(64) primary key,
    session_id varchar(64) not null,
    role varchar(32) not null,
    content text not null,
    model_name varchar(100),
    trace_id varchar(64) not null,
    created_at timestamp with time zone not null,
    constraint fk_message_session foreign key (session_id) references assistant_session(id)
);

create table if not exists assistant_context_snapshot (
    id varchar(64) primary key,
    session_id varchar(64) not null,
    project_id varchar(64) not null,
    page varchar(100) not null,
    selection_json text not null,
    allowed_resource_ids_json text not null,
    resource_versions_json text not null,
    context_hash varchar(128) not null,
    expires_at timestamp with time zone not null,
    created_at timestamp with time zone not null
);

create table if not exists assistant_plan (
    id varchar(64) primary key,
    session_id varchar(64) not null,
    context_snapshot_id varchar(64) not null,
    goal text not null,
    summary varchar(4000) not null,
    version integer not null,
    plan_hash varchar(128) not null,
    risk_level varchar(64) not null,
    status varchar(64) not null,
    affected_resources_json text not null,
    expires_at timestamp with time zone not null,
    confirmed_at timestamp with time zone,
    created_at timestamp with time zone not null
);

alter table assistant_plan add column if not exists execution_mode varchar(20) not null default 'APPROVAL';
alter table assistant_plan add column if not exists dynamic_agent boolean not null default false;
alter table assistant_plan add column if not exists tacit_task varchar(40);
alter table assistant_plan alter column goal type text;

create table if not exists assistant_plan_step (
    id varchar(64) primary key,
    plan_id varchar(64) not null,
    step_order integer not null,
    tool_name varchar(160) not null,
    tool_mode varchar(32) not null,
    title varchar(300) not null,
    description varchar(1000) not null,
    arguments_json text not null,
    risk_level varchar(64) not null,
    requires_confirmation boolean not null,
    status varchar(64) not null,
    constraint fk_step_plan foreign key (plan_id) references assistant_plan(id)
);

create table if not exists assistant_run (
    id varchar(64) primary key,
    session_id varchar(64) not null,
    plan_id varchar(64) not null,
    idempotency_key varchar(100) not null unique,
    trace_id varchar(64) not null,
    status varchar(64) not null,
    current_step integer not null default 0,
    result_summary text not null default '',
    created_at timestamp with time zone not null,
    started_at timestamp with time zone,
    finished_at timestamp with time zone
);

alter table assistant_run add column if not exists effects_json text not null default '{}';
alter table assistant_run add column if not exists gateway_token varchar(128) not null default '';
alter table assistant_run add column if not exists continuous_agent boolean not null default false;
alter table assistant_run add column if not exists approval_count integer not null default 0;

create table if not exists agent_work (
    run_id varchar(64) primary key references assistant_run(id) on delete cascade,
    stages_json text not null
);
create table if not exists artifact_review (
    id varchar(64) primary key,
    project_id varchar(64) not null references project(id),
    resource_id varchar(64) not null,
    resource_version integer not null,
    reviewed_by varchar(100) not null,
    reviewed_at timestamp with time zone not null
);
create index if not exists idx_artifact_review_project on artifact_review(project_id, reviewed_by);

create table if not exists assistant_event (
    id varchar(64) primary key,
    session_id varchar(64) not null,
    run_id varchar(64),
    event_seq bigint not null,
    event_type varchar(100) not null,
    payload_json text not null,
    created_at timestamp with time zone not null,
    constraint uq_event_seq unique (session_id, event_seq)
);

create index if not exists idx_session_project on assistant_session(project_id);
create index if not exists idx_plan_session on assistant_plan(session_id);
create index if not exists idx_event_session_seq on assistant_event(session_id, event_seq);

create table if not exists data_connection (
    id varchar(64) primary key,
    project_id varchar(64) not null,
    name varchar(200) not null,
    source_type varchar(32) not null,
    jdbc_url varchar(2000) not null,
    username varchar(300) not null default '',
    secret_ref text not null default '',
    options_json text not null,
    status varchar(32) not null,
    last_test_message varchar(1000) not null default '',
    last_tested_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_connection_project foreign key (project_id) references project(id)
);

alter table data_connection alter column secret_ref type text;

create table if not exists extract_job (
    id varchar(64) primary key,
    project_id varchar(64) not null,
    connection_id varchar(64) not null,
    name varchar(300) not null,
    sql_text text not null,
    sql_fingerprint varchar(128) not null,
    status varchar(32) not null,
    fetch_size integer not null,
    row_count bigint not null default 0,
    byte_count bigint not null default 0,
    output_path varchar(2000),
    output_name varchar(500),
    checksum varchar(128),
    error_message varchar(2000) not null default '',
    trace_id varchar(64) not null,
    heartbeat_at timestamp with time zone,
    created_at timestamp with time zone not null,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    constraint fk_extract_project foreign key (project_id) references project(id),
    constraint fk_extract_connection foreign key (connection_id) references data_connection(id)
);

create index if not exists idx_connection_project on data_connection(project_id);
create index if not exists idx_extract_project_created on extract_job(project_id, created_at);
create index if not exists idx_extract_status_heartbeat on extract_job(status, heartbeat_at);

create table if not exists file_resource (
    id varchar(64) primary key,
    project_id varchar(64) not null,
    name varchar(500) not null,
    media_type varchar(300) not null,
    status varchar(32) not null,
    current_version integer not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_file_project foreign key (project_id) references project(id)
);

create table if not exists file_version (
    id varchar(64) primary key,
    resource_id varchar(64) not null,
    version_number integer not null,
    original_name varchar(500) not null,
    media_type varchar(300) not null,
    storage_path varchar(2000) not null,
    size_bytes bigint not null,
    checksum varchar(128) not null,
    parse_status varchar(32) not null,
    parse_message varchar(2000) not null default '',
    created_at timestamp with time zone not null,
    parsed_at timestamp with time zone,
    constraint fk_version_resource foreign key (resource_id) references file_resource(id),
    constraint uq_resource_version unique (resource_id, version_number)
);

create table if not exists knowledge_ref (
    id varchar(128) primary key,
    project_id varchar(64) not null,
    resource_id varchar(64) not null,
    file_version_id varchar(64) not null,
    version_number integer not null,
    chunk_index integer not null,
    source_name varchar(500) not null,
    text_content text not null,
    location_json text not null,
    content_hash varchar(128) not null,
    created_at timestamp with time zone not null,
    constraint fk_ref_project foreign key (project_id) references project(id),
    constraint fk_ref_resource foreign key (resource_id) references file_resource(id),
    constraint fk_ref_version foreign key (file_version_id) references file_version(id),
    constraint uq_ref_version_chunk unique (file_version_id, chunk_index)
);

create index if not exists idx_file_project_updated on file_resource(project_id, updated_at);
create index if not exists idx_ref_project_resource on knowledge_ref(project_id, resource_id, version_number);

create table if not exists pasted_import_provenance (
    resource_id varchar(64) primary key,
    project_id varchar(64) not null,
    source_agent varchar(200) not null,
    source_question varchar(2000) not null,
    captured_at timestamp with time zone not null,
    detected_format varchar(32) not null,
    raw_hash varchar(128) not null,
    raw_content text not null,
    created_at timestamp with time zone not null,
    constraint fk_pasted_import_resource foreign key (resource_id) references file_resource(id),
    constraint fk_pasted_import_project foreign key (project_id) references project(id)
);

create index if not exists idx_pasted_import_project on pasted_import_provenance(project_id, created_at);

create table if not exists spreadsheet_conversion_job (
    resource_id varchar(64) primary key,
    version_number integer not null,
    status varchar(32) not null,
    rows_per_file integer not null,
    processed_rows bigint not null default 0,
    part_count integer not null default 0,
    error_message varchar(2000) not null default '',
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    updated_at timestamp with time zone not null,
    constraint fk_conversion_resource foreign key (resource_id) references file_resource(id)
);

create table if not exists spreadsheet_conversion_part (
    id varchar(64) primary key,
    resource_id varchar(64) not null,
    version_number integer not null,
    sequence_number integer not null,
    sheet_name varchar(500) not null,
    part_number integer not null,
    row_count bigint not null,
    name varchar(500) not null,
    storage_path varchar(2000) not null,
    size_bytes bigint not null,
    checksum varchar(128) not null,
    created_at timestamp with time zone not null,
    constraint fk_conversion_part_resource foreign key (resource_id) references file_resource(id),
    constraint uq_conversion_part_sequence unique (resource_id, version_number, sequence_number)
);

create index if not exists idx_conversion_part_resource on spreadsheet_conversion_part(resource_id, version_number);

create table if not exists deliverable_resource (
    id varchar(64) primary key,
    project_id varchar(64) not null,
    name varchar(300) not null,
    format varchar(32) not null,
    current_version integer not null,
    status varchar(32) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_deliverable_project foreign key (project_id) references project(id)
);

create table if not exists deliverable_version (
    id varchar(64) primary key,
    resource_id varchar(64) not null,
    version_number integer not null,
    storage_path varchar(2000) not null,
    size_bytes bigint not null,
    checksum varchar(128) not null,
    source_spec_json text not null,
    created_at timestamp with time zone not null,
    constraint fk_deliverable_version_resource foreign key (resource_id) references deliverable_resource(id),
    constraint uq_deliverable_version unique (resource_id, version_number)
);

create index if not exists idx_deliverable_project on deliverable_resource(project_id, updated_at);

create table if not exists workflow_definition (
    id varchar(64) primary key,
    project_id varchar(64) not null,
    name varchar(300) not null,
    description varchar(2000) not null default '',
    status varchar(32) not null,
    current_version integer not null,
    next_run_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_workflow_project foreign key (project_id) references project(id)
);

alter table workflow_definition add column if not exists next_run_at timestamp with time zone;

create table if not exists workflow_version (
    id varchar(64) primary key,
    workflow_id varchar(64) not null,
    version_number integer not null,
    definition_json text not null,
    created_at timestamp with time zone not null,
    constraint fk_workflow_version_definition foreign key (workflow_id) references workflow_definition(id),
    constraint uq_workflow_version unique (workflow_id, version_number)
);

create table if not exists workflow_run (
    id varchar(64) primary key,
    workflow_id varchar(64) not null,
    project_id varchar(64) not null,
    workflow_version integer not null,
    retry_of_run_id varchar(64),
    trigger_type varchar(32) not null default 'MANUAL',
    status varchar(32) not null,
    current_node_id varchar(100),
    output_json text not null default '{}',
    error_message varchar(2000) not null default '',
    trace_id varchar(64) not null,
    created_at timestamp with time zone not null,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    constraint fk_workflow_run_definition foreign key (workflow_id) references workflow_definition(id),
    constraint fk_workflow_run_project foreign key (project_id) references project(id)
);

alter table workflow_run add column if not exists trigger_type varchar(32) not null default 'MANUAL';

create table if not exists workflow_node_run (
    id varchar(64) primary key,
    run_id varchar(64) not null,
    node_id varchar(100) not null,
    node_name varchar(300) not null,
    node_type varchar(64) not null,
    step_order integer not null,
    status varchar(32) not null,
    input_json text not null default '{}',
    output_json text not null default '{}',
    error_message varchar(2000) not null default '',
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    constraint fk_workflow_node_run foreign key (run_id) references workflow_run(id),
    constraint uq_workflow_run_node unique (run_id, node_id)
);

create index if not exists idx_workflow_project_updated on workflow_definition(project_id, updated_at);
create index if not exists idx_workflow_next_run on workflow_definition(next_run_at);
create index if not exists idx_workflow_run_definition on workflow_run(workflow_id, created_at);
create index if not exists idx_workflow_node_run_run on workflow_node_run(run_id, step_order);

create table if not exists analysis_skill (
    id varchar(64) primary key,
    project_id varchar(64) not null,
    name varchar(200) not null,
    description varchar(1000) not null default '',
    instructions text not null,
    source_name varchar(500) not null,
    content_hash varchar(128) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_analysis_skill_project foreign key (project_id) references project(id),
    constraint uq_analysis_skill_name unique (project_id, name)
);

create index if not exists idx_analysis_skill_project on analysis_skill(project_id, updated_at);

create table if not exists workflow_activity_run (
    id varchar(64) primary key,
    run_id varchar(64) not null,
    node_run_id varchar(64) not null,
    activity_order integer not null,
    activity_type varchar(32) not null,
    capability varchar(200) not null default '',
    title varchar(300) not null,
    status varchar(32) not null,
    input_json text not null default '{}',
    output_json text not null default '{}',
    error_message varchar(2000) not null default '',
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    constraint fk_activity_run foreign key (run_id) references workflow_run(id),
    constraint fk_activity_node_run foreign key (node_run_id) references workflow_node_run(id),
    constraint uq_activity_order unique (node_run_id, activity_order)
);

create table if not exists workflow_lineage_edge (
    id varchar(64) primary key,
    run_id varchar(64) not null,
    node_run_id varchar(64),
    source_kind varchar(40) not null,
    source_ref varchar(200) not null,
    source_version integer,
    target_kind varchar(40) not null,
    target_ref varchar(200) not null,
    target_version integer,
    relation varchar(64) not null,
    details_json text not null default '{}',
    created_at timestamp with time zone not null,
    constraint fk_lineage_run foreign key (run_id) references workflow_run(id),
    constraint fk_lineage_node_run foreign key (node_run_id) references workflow_node_run(id)
);

create table if not exists workflow_run_event (
    id varchar(64) primary key,
    run_id varchar(64) not null,
    event_seq bigint not null,
    event_type varchar(100) not null,
    node_id varchar(100) not null default '',
    node_name varchar(300) not null default '',
    status varchar(32) not null default '',
    progress integer not null default 0,
    message varchar(2000) not null default '',
    content text not null default '',
    created_at timestamp with time zone not null,
    constraint fk_workflow_event_run foreign key (run_id) references workflow_run(id),
    constraint uq_workflow_event_seq unique (run_id, event_seq)
);

create table if not exists workflow_template (
    id varchar(64) primary key,
    name varchar(300) not null,
    description varchar(2000) not null default '',
    category varchar(100) not null default '通用',
    definition_json text not null,
    built_in boolean not null default false,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table if not exists agent_memory (
    id varchar(64) primary key,
    actor_id varchar(64) not null default 'default_user',
    project_id varchar(64),
    memory_scope varchar(32) not null,
    memory_key varchar(200) not null,
    value_json text not null,
    source_ref varchar(300) not null default '',
    status varchar(32) not null default 'ACTIVE',
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_agent_memory unique (actor_id, project_id, memory_scope, memory_key)
);

create index if not exists idx_activity_run_node on workflow_activity_run(run_id, node_run_id, activity_order);
create index if not exists idx_lineage_run on workflow_lineage_edge(run_id, node_run_id);
create index if not exists idx_lineage_target on workflow_lineage_edge(target_kind, target_ref);
create index if not exists idx_workflow_event_run on workflow_run_event(run_id, event_seq);
create index if not exists idx_agent_memory_scope on agent_memory(actor_id, project_id, memory_scope);

create table if not exists office_working_copy (
    source_kind varchar(32) not null,
    source_id varchar(64) not null,
    resource_id varchar(64) not null,
    created_at timestamp with time zone not null,
    primary key (source_kind, source_id),
    constraint fk_office_copy_resource foreign key (resource_id) references file_resource(id)
);

create table if not exists workspace_folder (
    id varchar(64) primary key,
    project_id varchar(64) not null,
    parent_id varchar(64),
    root_kind varchar(32) not null,
    name varchar(200) not null,
    sort_order integer not null default 0,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_workspace_folder_project foreign key (project_id) references project(id),
    constraint fk_workspace_folder_parent foreign key (parent_id) references workspace_folder(id),
    constraint uq_workspace_folder_name unique (project_id, parent_id, root_kind, name)
);

create table if not exists workspace_resource_location (
    project_id varchar(64) not null,
    resource_type varchar(64) not null,
    resource_id varchar(64) not null,
    folder_id varchar(64) not null,
    updated_at timestamp with time zone not null,
    primary key (project_id, resource_type, resource_id),
    constraint fk_workspace_location_project foreign key (project_id) references project(id),
    constraint fk_workspace_location_folder foreign key (folder_id) references workspace_folder(id)
);

create index if not exists idx_workspace_folder_project on workspace_folder(project_id, root_kind, parent_id, sort_order);
create index if not exists idx_workspace_location_folder on workspace_resource_location(folder_id);

create table if not exists assistant_model_settings (
    session_id varchar(64) primary key references assistant_session(id) on delete cascade,
    mode varchar(16) not null,
    base_url varchar(2000) not null,
    model varchar(200) not null,
    encrypted_key text not null
);

create table if not exists fixed_work_definition (
    id varchar(64) primary key,
    project_id varchar(64) not null references project(id),
    session_id varchar(64) not null references assistant_session(id),
    source_run_id varchar(64) not null references assistant_run(id),
    version integer not null,
    definition_json text not null,
    content_hash varchar(100) not null,
    verified boolean not null default false,
    created_at timestamp with time zone not null,
    unique(session_id, version)
);
create table if not exists fixed_work_run (
    id varchar(64) primary key,
    definition_id varchar(64) not null references fixed_work_definition(id),
    session_id varchar(64) not null references assistant_session(id),
    request_id varchar(64) not null,
    status varchar(32) not null,
    snapshot_json text not null,
    stages_json text not null,
    error text not null default '',
    created_at timestamp with time zone not null,
    finished_at timestamp with time zone,
    unique(session_id, request_id)
);

create table if not exists agent_work_title (
    project_id varchar(64) not null references project(id) on delete cascade,
    session_id varchar(64) not null references assistant_session(id) on delete cascade,
    title varchar(160) not null,
    primary key (project_id, session_id)
);

create table if not exists browser_user_grant (
    id varchar(64) primary key,
    owner_id varchar(160) not null,
    kind varchar(32) not null,
    scope_key varchar(100) not null,
    label varchar(1000) not null,
    status varchar(32) not null,
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone not null
);
create index if not exists idx_browser_grant_owner on browser_user_grant(owner_id, scope_key, status);
create table if not exists browser_approval_receipt (
    step_id varchar(64) primary key,
    owner_id varchar(160) not null,
    scope_key varchar(100) not null,
    consumed boolean not null default false
);
create table if not exists browser_authorization_audit (
    id varchar(64) primary key,
    owner_id varchar(160) not null,
    grant_id varchar(64) not null,
    step_id varchar(64) not null,
    event varchar(40) not null,
    label varchar(1000) not null,
    created_at timestamp with time zone not null
);
create index if not exists idx_browser_audit_owner on browser_authorization_audit(owner_id, created_at);
create table if not exists browser_authorized_session (
    session_id varchar(128) primary key,
    owner_id varchar(160) not null,
    grant_id varchar(64) not null,
    created_at timestamp with time zone not null
);

create table if not exists tacit_workspace (
    project_id varchar(64) primary key references project(id),
    revision bigint not null,
    selected_json text not null,
    source_stamp varchar(64) not null,
    state_json text not null
);

create table if not exists tacit_command_receipt (
    project_id varchar(64) not null references tacit_workspace(project_id),
    request_id varchar(128) not null,
    request_hash varchar(64) not null,
    committed_revision bigint not null,
    result_json text not null,
    actor_id varchar(64) not null,
    action varchar(40) not null,
    created_at timestamp with time zone not null,
    primary key (project_id, request_id)
);

create table if not exists tacit_review_event (
    project_id varchar(64) not null references tacit_workspace(project_id),
    revision bigint not null,
    action varchar(40) not null,
    target_id text not null,
    target_digest varchar(64) not null,
    target_json text not null,
    sources_json text not null,
    decision varchar(20) not null,
    actor_id varchar(64) not null,
    comment_text text not null,
    created_at timestamp with time zone not null,
    primary key (project_id, revision)
);
