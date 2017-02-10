-module(rabbit_mqtt_acl_redis).

-behaviour(gen_server).

-include("rabbit_mqtt.hrl").

-export([start_link/0,check_acl/3]).

-export([init/1, handle_call/3, handle_cast/2, handle_info/2,
         terminate/2, code_change/3]).

-record(acl_state, {acl_cmd}).

-define(REDIS_POOL_NAME,mqtt_redis).

%%----------------------------------------------------------------------------
start_link() ->
    gen_server:start_link({local, ?MODULE}, ?MODULE,[],[]).      

check_acl(ClientId, PubSub,Topic) ->
    gen_server:call(?MODULE, {check_acl, ClientId, PubSub, Topic}, infinity).  

%%----------------------------------------------------------------------------

init([]) ->
    {ok,RedisArgs} = application:get_env(?APP,redis),
    {ok,AclCmd} = application:get_env(?APP,acl_cmd),
    %%io:fwrite("get redis:~p~n",[application:get_env(?APP,redis)]),
    %%io:fwrite("get acl_cmd:~p~n",[application:get_env(?APP,acl_cmd)]),

    eredis_pool:start(),
    {ok,_} = eredis_pool:create_pool(?REDIS_POOL_NAME,
                            proplists:get_value(pool_size, RedisArgs, 50),
                            proplists:get_value(host, RedisArgs, "127.0.0.1"),
                            proplists:get_value(port, RedisArgs, 6379)),
    {ok, #acl_state{acl_cmd = AclCmd}}.

%%----------------------------------------------------------------------------    

handle_call({check_acl, ClientId, PubSub, Topic}, _From,
            State = #acl_state{acl_cmd     = AclCmd}) ->
    Cmd = string:tokens(replvar(AclCmd, ClientId), " "),
    rabbit_log:debug("MQTT check acl ~p: ~p,~p,~p~n",[ClientId,PubSub,Topic,Cmd]),
    %%io:fwrite("check acl,~p,~p,~p,~p,~p~n",[ClientId,PubSub,Topic,AclCmd,Cmd]),
    case eredis_pool:q(?REDIS_POOL_NAME, Cmd) of
        %%result example of eredis:q will be
        %%{ok,[<<"topic1">>,<<"1">>,<<"topic2">>,<<"2">>,<<"topic3">>,<<"3">>]}
        {ok, []}         -> {reply,{deny,"rule not set"},State};
        {ok, Rules}      -> case match(PubSub, Topic, Rules) of
                                allow   -> {reply,allow,State};
                                nomatch -> {reply,{deny,"rule not match"},State}
                            end;
        {error, Reason} -> rabbit_log:error("MQTT check acl error: ~p~n",[Reason]),
                           %%io:fwrite("Redis check_acl error: ~p~n", [Reason]),
                           {reply,{deny,Reason},State}

    end.

handle_info({'EXIT', _, {shutdown, closed}}, State) ->
    {stop, {shutdown, closed}, State}.

handle_cast(Msg, State) ->
    {stop, {unhandled_cast, Msg}, State}.

terminate(_Reason, _State) ->
    ok.

code_change(_OldVsn, State, _Extra) ->
    {ok, State}.

replvar(Cmd, ClientId) ->
    re:replace(Cmd, "~c", ClientId, [{return, list}]).

match(_PubSub, _Topic, []) ->
    nomatch;
match(PubSub, Topic, [Filter, Access | Rules]) ->
    case {match_topic(Topic, Filter), match_access(PubSub, b2i(Access))} of
        {true, true} -> allow;
        {_, _} -> match(PubSub, Topic, Rules)
    end.

match_topic(Topic, Filter) ->
    %%emqttd_topic:match(Topic, Filter).
    string:equal(Topic,binary_to_list(Filter)).

match_access(subscribe, Access) ->
    (1 band Access) > 0;
match_access(publish, Access) ->
    (2 band Access) > 0.
b2i(Bin) -> list_to_integer(binary_to_list(Bin)).


