
gen:
	@sbt 'runMain pca.PCACompBlockLarge'

test:
	@sbt test

test-all:
	@echo "Running all test configurations..."
	@failed=0; \
	total=0; \
	for config in configs/test_*.json; do \
		if [ -f "$$config" ]; then \
			total=$$((total + 1)); \
			echo ""; \
			echo "========================================="; \
			echo "Testing: $$config"; \
			echo "========================================="; \
			if python3 simulate.py "$$config" --test; then \
				echo "✓ $$config passed"; \
			else \
				echo "✗ $$config failed"; \
				failed=$$((failed + 1)); \
			fi; \
		fi; \
	done; \
	echo ""; \
	echo "========================================="; \
	echo "Test Summary: $$total total, $$((total - failed)) passed, $$failed failed"; \
	echo "========================================="; \
	if [ $$failed -gt 0 ]; then \
		exit 1; \
	fi

clean:
	rm -f *.anno.json
	rm -f *.fir
	rm -f *.v
	rm -rf generated/*
