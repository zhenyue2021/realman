package org.jeecg.common.aspect;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.jeecg.common.api.CommonAPI;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.Dict;
import org.jeecg.common.constant.CommonConstant;
import org.jeecg.common.system.vo.DictModel;
import org.jeecg.common.util.oConvertUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @Description: 字典aop类
 * @Author: dangzhenghui
 * @Date: 2019-3-17 21:50
 * @Version: 1.0
 */
@Aspect
@Component
@Slf4j
public class DictAspect {
    @Lazy
    @Autowired(required = false)
    private CommonAPI commonApi;
    @Autowired
    public RedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String JAVA_UTIL_DATE = "java.util.Date";

    /** 递归翻译时记录在哪个 JSON 节点、哪个字段名回填字典文本 */
    private static class FillBackItem {
        final JSONObject node;
        final String fieldName;
        final String dictCode;
        final String value;

        FillBackItem(JSONObject node, String fieldName, String dictCode, String value) {
            this.node = node;
            this.fieldName = fieldName;
            this.dictCode = dictCode;
            this.value = value;
        }
    }

    /**
     * 定义切点Pointcut
     *
     * 说明：
     * 1. 保持对原有 Result 返回值的兼容（旧系统逻辑不变）
     * 2. 额外拦截 org.jeecg.modules.device 包下所有 Controller 方法，
     *    以便对 IoT 模块中使用自定义 ApiResult 的接口做字典翻译（在 parseDictText 中通过反射判断 ApiResult）
     */
    @Pointcut("(@within(org.springframework.web.bind.annotation.RestController) || " +
            "@within(org.springframework.stereotype.Controller) || @annotation(org.jeecg.common.aspect.annotation.AutoDict)) " +
            "&& (execution(public org.jeecg.common.api.vo.Result org.jeecg..*.*(..)) " +
            "|| execution(public * org.jeecg.modules.device..*.*(..)))")
    public void excudeService() {
    }

    @Around("excudeService()")
    public Object doAround(ProceedingJoinPoint pjp) throws Throwable {
    	long time1=System.currentTimeMillis();	
        Object result = pjp.proceed();
        long time2=System.currentTimeMillis();
        log.debug("获取JSON数据 耗时："+(time2-time1)+"ms");
        long start=System.currentTimeMillis();
        result=this.parseDictText(result);
        long end=System.currentTimeMillis();
        log.debug("注入字典到JSON数据  耗时"+(end-start)+"ms");
        return result;
    }

    /**
     * 本方法针对返回对象为Result 的IPage的分页列表数据进行动态字典注入
     * 字典注入实现 通过对实体类添加注解@dict 来标识需要的字典内容,字典分为单字典code即可 ，table字典 code table text配合使用与原来jeecg的用法相同
     * 示例为SysUser   字段为sex 添加了注解@Dict(dicCode = "sex") 会在字典服务立马查出来对应的text 然后在请求list的时候将这个字典text，已字段名称加_dictText形式返回到前端
     * 例输入当前返回值的就会多出一个sex_dictText字段
     * {
     *      sex:1,
     *      sex_dictText:"男"
     * }
     * 前端直接取值sext_dictText在table里面无需再进行前端的字典转换了
     *  customRender:function (text) {
     *               if(text==1){
     *                 return "男";
     *               }else if(text==2){
     *                 return "女";
     *               }else{
     *                 return text;
     *               }
     *             }
     *             目前vue是这么进行字典渲染到table上的多了就很麻烦了 这个直接在服务端渲染完成前端可以直接用
     * @param result
     */
    private Object parseDictText(Object result) {
        if (result == null) {
            return null;
        }

        // 1. 旧逻辑：Result<T>（分页/列表/单对象）
        if (result instanceof Result<?> r) {
            Object inner = r.getResult();
            @SuppressWarnings("unchecked")
            Result<Object> cast = (Result<Object>) r;
            cast.setResult(translateAny(inner));
            return cast;
        }

        // 2. 适配 IoT 模块的 ApiResult<T>（通过反射避免直接依赖）
        try {
            Class<?> clazz = result.getClass();
            if ("org.jeecg.modules.device.vo.ApiResult".equals(clazz.getName())) {
                java.lang.reflect.Method getData = clazz.getMethod("getData");
                Object data = getData.invoke(result);
                Object translated = translateAny(data);
                java.lang.reflect.Method setData = clazz.getMethod("setData", Object.class);
                setData.invoke(result, translated);
            }
        } catch (Exception e) {
            log.warn("DictAspect parse ApiResult failed: {}", e.getMessage());
        }

        return result;
    }

    /**
     * 对任意返回数据进行字典翻译：
     * - IPage：分页记录
     * - List：列表
     * - 其它：单对象
     */
    private Object translateAny(Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof IPage<?> page) {
            translatePage(page);
            return page;
        }
        if (data instanceof List<?> list) {
            translateList(list);
            return list;
        }
        // 普通单对象
        return translateBean(data);
    }

    /**
     * 对分页数据中的记录做字典翻译
     */
    private void translatePage(IPage<?> page) {
        List<?> records = page.getRecords();
        List<JSONObject> items = translateRecords(records);
        if (items != null) {
            // raw type 警告可以忽略，底层序列化支持 JSONObject
            ((IPage) page).setRecords(items);
        }
    }

    /**
     * 对列表中的每个元素做字典翻译
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void translateList(List list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<JSONObject> items = translateRecords(list);
        if (items == null) {
            return;
        }
        list.clear();
        list.addAll(items);
    }

    /**
     * 对单个对象做字典翻译
     */
    private Object translateBean(Object bean) {
        List<Object> single = new ArrayList<>();
        single.add(bean);
        List<JSONObject> items = translateRecords(single);
        if (items == null || items.isEmpty()) {
            return bean;
        }
        return items.get(0);
    }

    /**
     * 核心翻译逻辑：将记录列表中的 @Dict 字段（含嵌套对象、嵌套 List）翻译为 *_dictText
     */
    @SuppressWarnings("unchecked")
    private List<JSONObject> translateRecords(List<?> records) {
        if (records == null || records.isEmpty()) {
            return null;
        }

        // 【VUEN-1230】 判断是否含有字典注解（含嵌套），没有注解返回 null
        Boolean hasDict = checkHasDictRecursive((List<Object>) records);
        if (!hasDict) {
            return null;
        }

        List<JSONObject> items = new ArrayList<>();
        Map<String, List<String>> dataListMap = new HashMap<>(5);
        List<FillBackItem> fillBackList = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        log.debug(" __ 进入字典翻译切面 DictAspect —— " );
        for (Object record : records) {
            String json = "{}";
            try {
                json = objectMapper.writeValueAsString(record);
            } catch (JsonProcessingException e) {
                log.error("json解析失败" + e.getMessage(), e);
            }
            JSONObject item = JSONObject.parseObject(json, Feature.OrderedField);
            items.add(item);
            collectDictFromBean(record, item, dataListMap, fillBackList, visited);
        }

        Map<String, List<DictModel>> translText = this.translateAllDict(dataListMap);

        for (FillBackItem fb : fillBackList) {
            List<DictModel> dictModels = translText.get(fb.dictCode);
            if (dictModels == null || dictModels.isEmpty()) {
                continue;
            }
            String textValue = this.translDictText(dictModels, fb.value);
            log.debug(" __翻译字典字段__ " + fb.fieldName + CommonConstant.DICT_TEXT_SUFFIX + "： " + textValue);
            fb.node.put(fb.fieldName + CommonConstant.DICT_TEXT_SUFFIX, textValue);
        }

        return items;
    }

    /**
     * list 去重添加
     */
    private void listAddAllDeduplicate(List<String> dataList, List<String> addList) {
        // 筛选出dataList中没有的数据
        List<String> filterList = addList.stream().filter(i -> !dataList.contains(i)).collect(Collectors.toList());
        dataList.addAll(filterList);
    }

    /** 从 JSON 或 Java 取值转为字符串（兼容数字等非字符串类型） */
    private static String getDictValueString(Object jsonVal, Object fieldVal) {
        if (jsonVal != null) {
            return String.valueOf(jsonVal);
        }
        return fieldVal != null ? String.valueOf(fieldVal) : null;
    }

    /** 是否为需要递归扫描的 bean 类型（排除基本类型、String、数字、日期、Map、List 等） */
    private static boolean isBeanType(Object obj) {
        if (obj == null) {
            return false;
        }
        Class<?> c = obj.getClass();
        if (c.isPrimitive() || c.isEnum()) {
            return false;
        }
        if (c == String.class || c == Boolean.class || Boolean.TYPE == c) {
            return false;
        }
        if (Number.class.isAssignableFrom(c) || Integer.TYPE == c || Long.TYPE == c || Double.TYPE == c || Float.TYPE == c) {
            return false;
        }
        if (Date.class.isAssignableFrom(c)) {
            return false;
        }
        if (Map.class.isAssignableFrom(c) || List.class.isAssignableFrom(c)) {
            return false;
        }
        return true;
    }

    /**
     * 递归收集当前 bean 及嵌套对象、列表中的 @Dict 字段，并回填到对应 JSON 节点
     */
    private void collectDictFromBean(Object bean, JSONObject jsonNode,
                                     Map<String, List<String>> dataListMap,
                                     List<FillBackItem> fillBackList,
                                     Set<Object> visited) {
        if (bean == null || jsonNode == null || visited.contains(bean)) {
            return;
        }
        visited.add(bean);
        try {
            for (Field field : oConvertUtils.getAllFields(bean)) {
                field.setAccessible(true);
                Object fv;
                try {
                    fv = field.get(bean);
                } catch (Exception e) {
                    log.trace("get field {} failed: {}", field.getName(), e.getMessage());
                    continue;
                }
                Object jv = jsonNode.get(field.getName());
                Dict dict = field.getAnnotation(Dict.class);
                if (dict != null) {
                    String value = getDictValueString(jv, fv);
                    if (oConvertUtils.isNotEmpty(value)) {
                        String code = dict.dicCode();
                        String text = dict.dicText();
                        String table = dict.dictTable();
                        String dataSource = dict.ds();
                        String dictCode = StringUtils.isEmpty(table) ? code : String.format("%s,%s,%s,%s", table, text, code, dataSource);
                        List<String> dataList = dataListMap.computeIfAbsent(dictCode, k -> new ArrayList<>());
                        listAddAllDeduplicate(dataList, Arrays.asList(value.split(",")));
                        fillBackList.add(new FillBackItem(jsonNode, field.getName(), dictCode, value));
                    }
                    continue;
                }
                if (fv instanceof List && jv instanceof JSONArray) {
                    List<?> list = (List<?>) fv;
                    JSONArray arr = (JSONArray) jv;
                    for (int i = 0; i < list.size() && i < arr.size(); i++) {
                        processValue(list.get(i), arr.get(i), dataListMap, fillBackList, visited);
                    }
                    continue;
                }
                if (isBeanType(fv) && jv instanceof JSONObject) {
                    collectDictFromBean(fv, (JSONObject) jv, dataListMap, fillBackList, visited);
                }
            }
        } finally {
            visited.remove(bean);
        }
    }

    /**
     * 处理单个值：可能是 bean 或 list，递归收集 @Dict 并回填
     */
    private void processValue(Object javaVal, Object jsonVal,
                              Map<String, List<String>> dataListMap,
                              List<FillBackItem> fillBackList,
                              Set<Object> visited) {
        if (javaVal == null) {
            return;
        }
        if (javaVal instanceof List && jsonVal instanceof JSONArray) {
            List<?> list = (List<?>) javaVal;
            JSONArray arr = (JSONArray) jsonVal;
            for (int i = 0; i < list.size() && i < arr.size(); i++) {
                processValue(list.get(i), arr.get(i), dataListMap, fillBackList, visited);
            }
            return;
        }
        if (isBeanType(javaVal) && jsonVal instanceof JSONObject) {
            collectDictFromBean(javaVal, (JSONObject) jsonVal, dataListMap, fillBackList, visited);
        }
    }

    /**
     * 一次性把所有的字典都翻译了
     * 1.  所有的普通数据字典的所有数据只执行一次SQL
     * 2.  表字典相同的所有数据只执行一次SQL
     * @param dataListMap
     * @return
     */
    private Map<String, List<DictModel>> translateAllDict(Map<String, List<String>> dataListMap) {
        // 翻译后的字典文本，key=dictCode
        Map<String, List<DictModel>> translText = new HashMap<>(5);
        // 当前服务未引入系统字典服务（CommonAPI）时，直接跳过翻译，避免启动/运行报错
        if (commonApi == null) {
            return translText;
        }
        // 需要翻译的数据（有些可以从redis缓存中获取，就不走数据库查询）
        List<String> needTranslData = new ArrayList<>();
        //step.1 先通过redis中获取缓存字典数据
        for (String dictCode : dataListMap.keySet()) {
            List<String> dataList = dataListMap.get(dictCode);
            if (dataList.size() == 0) {
                continue;
            }
            // 表字典需要翻译的数据
            List<String> needTranslDataTable = new ArrayList<>();
            for (String s : dataList) {
                String data = s.trim();
                if (data.length() == 0) {
                    continue; //跳过循环
                }
                if (dictCode.contains(",")) {
                    String keyString = String.format("sys:cache:dictTable::SimpleKey [%s,%s]", dictCode, data);
                    if (redisTemplate.hasKey(keyString)) {
                        try {
                            String text = oConvertUtils.getString(redisTemplate.opsForValue().get(keyString));
                            List<DictModel> list = translText.computeIfAbsent(dictCode, k -> new ArrayList<>());
                            list.add(new DictModel(data, text));
                        } catch (Exception e) {
                            log.warn(e.getMessage());
                        }
                    } else if (!needTranslDataTable.contains(data)) {
                        // 去重添加
                        needTranslDataTable.add(data);
                    }
                } else {
                    String keyString = String.format("sys:cache:dict::%s:%s", dictCode, data);
                    if (redisTemplate.hasKey(keyString)) {
                        try {
                            String text = oConvertUtils.getString(redisTemplate.opsForValue().get(keyString));
                            List<DictModel> list = translText.computeIfAbsent(dictCode, k -> new ArrayList<>());
                            list.add(new DictModel(data, text));
                        } catch (Exception e) {
                            log.warn(e.getMessage());
                        }
                    } else if (!needTranslData.contains(data)) {
                        // 去重添加
                        needTranslData.add(data);
                    }
                }

            }
            //step.2 调用数据库翻译表字典
            if (needTranslDataTable.size() > 0) {
                String[] arr = dictCode.split(",");
                String table = arr[0], text = arr[1], code = arr[2];
                String values = String.join(",", needTranslDataTable);
                // 自定义的数据源
                String dataSource = null;
                if (arr.length > 3) {
                    dataSource = arr[3];
                }
                log.debug("translateDictFromTableByKeys.dictCode:" + dictCode);
                log.debug("translateDictFromTableByKeys.values:" + values);
                
                // 代码逻辑说明: 微服务下为空报错没有参数需要传递空字符串---
                if(null == dataSource){
                    dataSource = "";
                }
                
                List<DictModel> texts = commonApi.translateDictFromTableByKeys(table, text, code, values, dataSource);
                log.debug("translateDictFromTableByKeys.result:" + texts);
                List<DictModel> list = translText.computeIfAbsent(dictCode, k -> new ArrayList<>());
                list.addAll(texts);

                // 做 redis 缓存
                for (DictModel dict : texts) {
                    String redisKey = String.format("sys:cache:dictTable::SimpleKey [%s,%s]", dictCode, dict.getValue());
                    try {
                        // 保留5分钟
                        redisTemplate.opsForValue().set(redisKey, dict.getText(), 300, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.warn(e.getMessage(), e);
                    }
                }
            }
        }

        //step.3 调用数据库进行翻译普通字典
        if (needTranslData.size() > 0) {
            List<String> dictCodeList = Arrays.asList(dataListMap.keySet().toArray(new String[]{}));
            // 将不包含逗号的字典code筛选出来，因为带逗号的是表字典，而不是普通的数据字典
            List<String> filterDictCodes = dictCodeList.stream().filter(key -> !key.contains(",")).collect(Collectors.toList());
            String dictCodes = String.join(",", filterDictCodes);
            String values = String.join(",", needTranslData);
            log.debug("translateManyDict.dictCodes:" + dictCodes);
            log.debug("translateManyDict.values:" + values);
            Map<String, List<DictModel>> manyDict = commonApi.translateManyDict(dictCodes, values);
            log.debug("translateManyDict.result:" + manyDict);
            for (String dictCode : manyDict.keySet()) {
                List<DictModel> list = translText.computeIfAbsent(dictCode, k -> new ArrayList<>());
                List<DictModel> newList = manyDict.get(dictCode);
                list.addAll(newList);

                // 做 redis 缓存
                for (DictModel dict : newList) {
                    String redisKey = String.format("sys:cache:dict::%s:%s", dictCode, dict.getValue());
                    try {
                        redisTemplate.opsForValue().set(redisKey, dict.getText());
                    } catch (Exception e) {
                        log.warn(e.getMessage(), e);
                    }
                }
            }
        }
        return translText;
    }

    /**
     * 字典值替换文本
     *
     * @param dictModels
     * @param values
     * @return
     */
    private String translDictText(List<DictModel> dictModels, String values) {
        List<String> result = new ArrayList<>();

        // 允许多个逗号分隔，允许传数组对象
        String[] splitVal = values.split(",");
        for (String val : splitVal) {
            String dictText = val;
            for (DictModel dict : dictModels) {
                if (val.equals(dict.getValue())) {
                    dictText = dict.getText();
                    break;
                }
            }
            result.add(dictText);
        }
        return String.join(",", result);
    }

    /**
     *  翻译字典文本
     * @param code
     * @param text
     * @param table
     * @param key
     * @return
     */
    @Deprecated
    private String translateDictValue(String code, String text, String table, String key) {
    	if(oConvertUtils.isEmpty(key)) {
    		return null;
    	}
        StringBuffer textValue=new StringBuffer();
        String[] keys = key.split(",");
        for (String k : keys) {
            String tmpValue = null;
            log.debug(" 字典 key : "+ k);
            if (k.trim().length() == 0) {
                continue; //跳过循环
            }
            // 代码逻辑说明: !56 优化微服务应用下存在表字段需要字典翻译时加载缓慢问题-----
            if (!StringUtils.isEmpty(table)){
                log.debug("--DictAspect------dicTable="+ table+" ,dicText= "+text+" ,dicCode="+code);
                String keyString = String.format("sys:cache:dictTable::SimpleKey [%s,%s,%s,%s]",table,text,code,k.trim());
                    if (redisTemplate.hasKey(keyString)){
                    try {
                        tmpValue = oConvertUtils.getString(redisTemplate.opsForValue().get(keyString));
                    } catch (Exception e) {
                        log.warn(e.getMessage());
                    }
                }else {
                    tmpValue= commonApi.translateDictFromTable(table,text,code,k.trim());
                }
            }else {
                String keyString = String.format("sys:cache:dict::%s:%s",code,k.trim());
                if (redisTemplate.hasKey(keyString)){
                    try {
                        tmpValue = oConvertUtils.getString(redisTemplate.opsForValue().get(keyString));
                    } catch (Exception e) {
                       log.warn(e.getMessage());
                    }
                }else {
                    tmpValue = commonApi.translateDict(code, k.trim());
                }
            }

            if (tmpValue != null) {
                if (!"".equals(textValue.toString())) {
                    textValue.append(",");
                }
                textValue.append(tmpValue);
            }

        }
        return textValue.toString();
    }

    /**
     * 检测返回结果集中是否包含 Dict 注解（含嵌套对象、嵌套 List）
     */
    private Boolean checkHasDictRecursive(List<Object> records) {
        if (records == null || records.isEmpty()) {
            return false;
        }
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object record : records) {
            if (checkHasDictInObject(record, visited)) {
                return true;
            }
        }
        return false;
    }

    private Boolean checkHasDictInObject(Object obj, Set<Object> visited) {
        if (obj == null || visited.contains(obj)) {
            return false;
        }
        visited.add(obj);
        try {
            if (obj instanceof List) {
                for (Object e : (List<?>) obj) {
                    if (checkHasDictInObject(e, visited)) {
                        return true;
                    }
                }
                return false;
            }
            if (!isBeanType(obj)) {
                return false;
            }
            for (Field field : oConvertUtils.getAllFields(obj)) {
                if (field.getAnnotation(Dict.class) != null) {
                    return true;
                }
                field.setAccessible(true);
                Object fv;
                try {
                    fv = field.get(obj);
                } catch (Exception e) {
                    continue;
                }
                if (fv instanceof List) {
                    for (Object e : (List<?>) fv) {
                        if (checkHasDictInObject(e, visited)) {
                            return true;
                        }
                    }
                } else if (isBeanType(fv) && checkHasDictInObject(fv, visited)) {
                    return true;
                }
            }
            return false;
        } finally {
            visited.remove(obj);
        }
    }

}
