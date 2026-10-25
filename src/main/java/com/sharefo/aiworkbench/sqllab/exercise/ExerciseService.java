package com.sharefo.aiworkbench.sqllab.exercise;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.sharefo.aiworkbench.sqllab.dto.ExerciseVO;

import tools.jackson.databind.ObjectMapper;

@Service
public class ExerciseService {

	private final List<ExerciseVO> exercises;

	public ExerciseService(ObjectMapper mapper) {
		try (InputStream in = new ClassPathResource("sqllab/exercises.json").getInputStream()) {
			ExerciseVO[] arr = mapper.readValue(in, ExerciseVO[].class);
			this.exercises = List.of(arr);
		}
		catch (Exception e) {
			throw new IllegalStateException("加载练习题失败：" + e.getMessage(), e);
		}
	}

	public List<ExerciseVO> list() {
		return exercises;
	}

	public Optional<ExerciseVO> find(String id) {
		return exercises.stream().filter(e -> e.getId().equals(id)).findFirst();
	}
}
