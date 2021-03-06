/**
 * Copyright 2019 vip.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.vip.pallas.console.controller.index.version;

import static java.util.stream.Collectors.toList;

import java.sql.SQLException;
import java.util.*;

import javax.annotation.Resource;

import com.vip.pallas.bean.DBSchema;
import com.vip.pallas.console.vo.TemplateFieldVO;
import com.vip.pallas.entity.BusinessLevelExceptionCode;
import org.apache.commons.collections.map.HashedMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vip.pallas.exception.BusinessLevelException;
import com.vip.pallas.exception.PallasException;
import com.vip.pallas.mybatis.entity.Index;
import com.vip.pallas.mybatis.entity.IndexVersion;
import com.vip.pallas.service.ClusterService;
import com.vip.pallas.service.ElasticSearchService;
import com.vip.pallas.service.IndexService;
import com.vip.pallas.service.IndexVersionService;
import com.vip.pallas.utils.ObjectMapTool;

@RestController
public class IndexFindMetaDataController {
	
	@Autowired	
	private IndexVersionService indexVersionService;

	@Resource
	private IndexService indexService;

	@Autowired
	private ClusterService clusterService;

	@Resource
	private ElasticSearchService elasticSearchService;

	@RequestMapping(value = "/index/version/metadata.json")
	public Map<String, Object> metadata(@RequestBody Map<String, Object> params) throws SQLException, PallasException { // NOSONAR
		Long indexId =  ObjectMapTool.getLong(params, "indexId");
		if(ObjectUtils.isEmpty(indexId)){
			throw new BusinessLevelException(BusinessLevelExceptionCode.HTTP_INTERNAL_SERVER_ERROR, "indexId不能为空");
		}
		
		Map<String, Object> resultMap = new HashMap<>();
		List<DBSchema> dbList = indexVersionService.getMetaDataFromDB(indexId);
		resultMap.put("list", dbList);
		com.vip.pallas.bean.IndexVersion version = indexVersionService.findUsedOrLastIndexVersoinByIndexId(indexId);

		List<TemplateFieldVO> schema;
		if (null == version){
			schema = dbList.stream().map(filed->new TemplateFieldVO(filed.getDbFieldName())).collect(toList());
		}else {
			schema = new ArrayList<>();
			 version.getSchema().stream().filter(field -> field.getFieldType()!="nested"&&field.getFieldType()!="object"&&(field.getMultiField()==null||field.getMultiField().size()==0)).forEach(field -> {
				 schema.add(new TemplateFieldVO(field.getFieldName()));
			});
		}
		resultMap.put("schema",schema);

		resultMap.put("clusters", getClusters(indexId));
		resultMap.put("isLogical", indexService.isLogicalIndex(indexId));
		return resultMap;
	}

	private List<Map<String, Object>> getClusters(Long indexId) {

		List<IndexVersion> versions = indexVersionService.findAll();
		List<Index> indices = indexService.findAll();
		Map<String, String> versionIndexMap = new HashMap<>();
		versions.forEach(
				v -> {
					Optional<Index> op = indices.stream().filter(index -> index.getId().equals(v.getIndexId())).findFirst();
					if (op.isPresent()) {
						String indexName = op.get().getIndexName();
						versionIndexMap.put(indexName + "_" + v.getId(), indexName);
					}
				}
		);

		return clusterService.selectPhysicalClustersByIndexId(indexId)
				.stream()
				.map(cluster -> {
					Map<String, Object> map = new HashedMap();
					map.put("id", cluster.getId());
					map.put("clusterId", cluster.getClusterId());
					map.put("nodes", getNodes(cluster.getClusterId(), versionIndexMap));
					return map;
				})
				.collect(toList());

	}

	private List<Map<String, Object>> getNodes(String cluster, Map<String, String> versionIndexMap) {
		try {
			List<String[]> nodes = elasticSearchService.getNodes(cluster);
			List<String[]> shards = elasticSearchService.getShards(cluster);
			return nodes.stream()
					.filter(node -> node[1] != null && node[1].contains("di"))
					.map(node ->{
						Map<String, Object> map = new HashMap<>();
						map.put("name", node[2]);
						map.put("indicis", getIndexListForNode(node[2], shards, versionIndexMap));
						return map;
					})
					.sorted(Comparator.comparing(map -> ((String) map.get("name"))))
					.collect(toList());
		} catch (Exception ignore) {
			return Collections.emptyList();
		}
	}

	private List<String> getIndexListForNode(String nodeName, List<String[]> shards, Map<String, String> versionIndexMap) {
		return shards.stream()
				.filter(s -> !s[0].startsWith(".") && s[2].equals(nodeName))
				.map(s -> versionIndexMap.getOrDefault(s[0], s[0]))
				.distinct()
				.collect(toList());
	}
}